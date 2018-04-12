package org.rzlabs.druid.client

import java.io.IOException
import java.util.concurrent.ExecutorService

import org.apache.curator.framework.api.CompressionProvider
import org.apache.curator.framework.imps.GzipCompressionProvider
import org.apache.curator.framework.recipes.cache.PathChildrenCache.StartMode
import org.apache.curator.framework.recipes.cache.{ChildData, PathChildrenCache, PathChildrenCacheEvent, PathChildrenCacheListener}
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.BoundedExponentialBackoffRetry
import org.apache.curator.utils.ZKPaths
import org.apache.spark.sql.MyLogging
import org.json4s._
import org.json4s.jackson.JsonMethods._
import org.rzlabs.druid.metadata.{DruidClusterInfo, DruidNode, DruidOptions}
import org.rzlabs.druid.{DruidDataSourceException, Utils}

import scala.collection.mutable.{Map => MMap}

class CuratorConnection(val zkHost: String,
                        val options: DruidOptions,
                        val cache: MMap[String, DruidClusterInfo],
                        execSvc: ExecutorService,
                        updateTimeBoundary: Array[Byte] => Unit
                        ) extends MyLogging {

  import Utils.jsonFormat

  // Cache the active historical servers.
  val serverSegmentsCacheMap: MMap[String, PathChildrenCache] = MMap()
  val serverSegmentsCacheLock = new Object
  var brokers: Vector[String] = Vector()
  val brokersCacheLock = new Object

  val announcementsPath = ZKPaths.makePath(options.zkDruidPath, "announcements")
  val serverSegmentsPath = ZKPaths.makePath(options.zkDruidPath, "segments")
  val discoveryPath = ZKPaths.makePath(options.zkDruidPath, "discovery")
  val brokersPath = ZKPaths.makePath(discoveryPath,
    if (options.zkQualifyDiscoveryNames) s"${options.zkDruidPath}:broker" else "broker")

  val framework: CuratorFramework = CuratorFrameworkFactory.builder
    .connectString(zkHost)
    .sessionTimeoutMs(options.zkSessionTimeoutMs)
    .retryPolicy(new BoundedExponentialBackoffRetry(1000, 45000, 30))
    .compressionProvider(new PotentiallyGzippedCompressionProvider(options.zkEnableCompression))
    .build()

  val announcementsCache: PathChildrenCache = new PathChildrenCache(
    framework,
    announcementsPath,
    true,
    true,
    execSvc
  )

  val brokersCache: PathChildrenCache = new PathChildrenCache(
    framework,
    brokersPath,
    true,
    true,
    execSvc
  )

  /**
   * A [[PathChildrenCacheListener]] which is used to monitor brokers' in and out.
   */
  val brokersListener = new PathChildrenCacheListener {
    override def childEvent(client: CuratorFramework, event: PathChildrenCacheEvent): Unit = {
      event.getType match {
        case eventType @ PathChildrenCacheEvent.Type.CHILD_ADDED |
             PathChildrenCacheEvent.Type.CHILD_REMOVED =>
          val data = getZkDataForNode(event.getData.getPath)
          val druidNode = parse(new String(data)).extract[DruidNode]
          val host = s"${druidNode.address}:${druidNode.port}"
          if (eventType == PathChildrenCacheEvent.Type.CHILD_ADDED) {
            brokersCacheLock.synchronized {
              if (brokers.contains(host)) {
                logError("New broker[%s] but there was already one, ignoreing new one.", host)
              } else {
                brokers = brokers :+ host
                logDebug("New broker[%s] is added to cache.", host)
              }
            }
          } else {
            brokersCacheLock.synchronized {
              if (brokers.contains(host)) {
                brokers = brokers.filterNot(_ == host)
                logDebug("Broker[%] is offline, so remove it from the cache.", host)
              } else {
                logError("Broker[%s] is not in the cache, how to remove it from cache?", host)
              }
            }

          }
        case _ => ()
      }
    }
  }

  /**
   * A [[PathChildrenCacheListener]] which is used to monitor segments' in and out and
   * update time boundary for datasources.
   * The occurrence of a CHILD_ADDED event means there's a new segment of some datasource
   * is announced, and we should update the datasource's time boundary.
   * The occurrence of a CHILD_REMOVED event means there's a segment of some
   * datasource is removed, and we should update the datasource's time boundary.
   */
  val segmentsListener = new PathChildrenCacheListener {
    override def childEvent(client: CuratorFramework, event: PathChildrenCacheEvent): Unit = {
      event.getType match {
        case eventType @ PathChildrenCacheEvent.Type.CHILD_ADDED |
             PathChildrenCacheEvent.Type.CHILD_REMOVED =>
          logDebug(s"event ${event.getType} occurred.")
          val nodeData = getZkDataForNode(event.getData.getPath)
          if (nodeData == null) {
            logWarning("Ignoring event: Type - %s, Path - %s, Version - %s",
              Array(event.getType, event.getData.getPath, event.getData.getStat.getVersion))
          } else {
            updateTimeBoundary(nodeData)
          }
        case _ => ()
      }
    }
  }

  /**
   * A [[PathChildrenCacheListener]] which is used to monitor historical servers' in and out
   * and manage the relationships of historical servers and their [[PathChildrenCache]]s.
   */
  val announcementsListener = new PathChildrenCacheListener {
    override def childEvent(client: CuratorFramework, event: PathChildrenCacheEvent): Unit = {
      event.getType match {
        case PathChildrenCacheEvent.Type.CHILD_ADDED =>
          // New historical server is added to the Druid cluster.
          serverSegmentsCacheLock.synchronized {
            // Get the historical server addr from path child data.
            val key = getServerKey(event)
            if (serverSegmentsCacheMap.contains(key)) {
              logError("New historical[%s] but there was already one, ignoreing new one.", key)
            } else if (key != null) {
              val segmentsPath = ZKPaths.makePath(serverSegmentsPath, key)
              val segmentsCache = new PathChildrenCache(
                framework,
                segmentsPath,
                true,
                true,
                execSvc
              )
              segmentsCache.getListenable.addListener(segmentsListener)
              serverSegmentsCacheMap(key) = segmentsCache
              logDebug("Starting inventory cache for %s, inventoryPath %s", Array(key, segmentsPath))
              // Start cache and trigger the CHILD_ADDED event.
              //segmentsCache.start(StartMode.POST_INITIALIZED_EVENT)
              // Start cache and do not trigger the CHILD_ADDED by default.
              segmentsCache.start(StartMode.BUILD_INITIAL_CACHE)
            }
          }
        case PathChildrenCacheEvent.Type.CHILD_REMOVED =>
          // A historical server is offline.
          serverSegmentsCacheLock.synchronized {
            val key = getServerKey(event)
            val segmentsCache: Option[PathChildrenCache] = serverSegmentsCacheMap.remove(key)
            if (segmentsCache.isDefined) {
              logInfo("Closing inventory ache for %s. Also removin listeners.", key)
              segmentsCache.get.getListenable.clear()
              segmentsCache.get.close()
            } else logWarning("Cache[%s] removed that wasn't cache!?", key)
          }
        case _ => ()
      }
    }
  }

  announcementsCache.getListenable.addListener(announcementsListener)
  brokersCache.getListenable.addListener(brokersListener)

  framework.start()
  announcementsCache.start(StartMode.BUILD_INITIAL_CACHE)
  brokersCache.start(StartMode.POST_INITIALIZED_EVENT)

  def getService(name: String): String = {
    getServices(name).head
  }

  def getServices(name: String): Seq[String] = {

    val serviceName = if (options.zkQualifyDiscoveryNames) s"${options.zkDruidPath}:$name" else name
    val servicePath = ZKPaths.makePath(discoveryPath, serviceName)
    val childrenNodes: java.util.List[String] = framework.getChildren.forPath(servicePath)
    var services: Seq[String] = Nil
    try {
      for (childNode <- childrenNodes) {
        val childPath = ZKPaths.makePath(servicePath, childNode)
        val data: Array[Byte] = getZkDataForNode(childPath)
        if (data != null) {
          val druidNode = parse(new String(data)).extract[DruidNode]
          services = services :+ s"${druidNode.address}:${druidNode.port}"
        }
      }
    } catch {
      case e: Exception =>
        throw new DruidDataSourceException(s"Failed to get '$name' for '$zkHost'", e)
    }
    if (services.isEmpty) {
      throw new DruidDataSourceException(s"There's no '$name' for 'zkHost' in path '$servicePath'")
    }
    services
  }

  def getBroker: String = {
    brokersCacheLock.synchronized {
      if (brokers.isEmpty) {
        brokers = getServices("broker").toVector
      }
      val broker = brokers.head
      // Get broker in the round-robin manner.
      brokers = brokers.tail :+ broker
      broker
    }
  }

  private def getServerKey(event: PathChildrenCacheEvent): String = {
    val child: ChildData = event.getData
    val data: Array[Byte] = getZkDataForNode(child.getPath)
    if (data == null) {
      logWarning("Ignoring event: Type - %s, Path - %s, Version - %s",
        Array(event.getType, child.getPath, child.getStat.getVersion))
      null
    } else {
      ZKPaths.getNodeFromPath(child.getPath)
    }
  }

  private def getZkDataForNode(path: String): Array[Byte] = {
    try {
      framework.getData.decompressed().forPath(path)
    } catch {
      case e: Exception => {
        logError(s"Exception occurs while getting data fro node $path", e)
        null
      }
    }
  }
}

/*
 * copied from druid code base.
 */
class PotentiallyGzippedCompressionProvider(val compressOutput: Boolean)
  extends CompressionProvider {

  private val base: GzipCompressionProvider = new GzipCompressionProvider


  @throws[Exception]
  def compress(path: String, data: Array[Byte]): Array[Byte] = {
    return if (compressOutput) base.compress(path, data)
    else data
  }

  @throws[Exception]
  def decompress(path: String, data: Array[Byte]): Array[Byte] = {
    try {
      return base.decompress(path, data)
    }
    catch {
      case e: IOException => {
        return data
      }
    }
  }
}