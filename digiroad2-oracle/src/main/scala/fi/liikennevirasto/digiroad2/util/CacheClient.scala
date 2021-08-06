package fi.liikennevirasto.digiroad2.util

import net.spy.memcached.{AddrUtil, ConnectionFactory, ConnectionFactoryBuilder, MemcachedClient}
import net.spy.memcached.transcoders.SerializingTranscoder
import org.slf4j.{Logger, LoggerFactory}


case class CachedValue(data: Any, success: Boolean)

class CacheClient {
  val logger: Logger = LoggerFactory.getLogger(getClass)
  //in second
  val twentyHours: Int = 72000

  lazy val transcoder = new SerializingTranscoder(50 * 1024 * 1024)
  lazy val connectionFactory: ConnectionFactory = new ConnectionFactoryBuilder()
    .setOpTimeout(1000000L)
    .setTranscoder(transcoder).build()

  lazy val client = new MemcachedClient(connectionFactory,
    AddrUtil.getAddresses(
      Digiroad2Properties.cacheHostname + ":" + Digiroad2Properties.cacheHostPort))

  def set[DataModel](key: String, ttl: Int, data: DataModel): DataModel = {
    try {
      client.set(key, ttl, data).isDone
      client.get(key).asInstanceOf[DataModel]
    } catch {
      case e: Exception => logger.error("Caching failed", e); throw e
    }
  }

  def get[DataModel](key: String): CachedValue = {
    try {
      val result = client.get(key).asInstanceOf[DataModel]
      if (result == null) {
        CachedValue(null, success = false)
      } else {
        CachedValue(result, success = true)
      }
    } catch {
      case e: Exception => logger.error("Retrieval of cached value failed", e); throw e
    }
  }
}

object Caching extends CacheClient {
  def cache[DataModel](f: => DataModel)(method: String,
                                        parameter: String): DataModel = {
    val generatedKey = generateKey(method, parameter = parameter)

    get[DataModel](generatedKey) match {
      case CachedValue(data, true) => println("return cached value"); data.asInstanceOf[DataModel]
      case _ => println("cache value")
        println("saving with value " + generatedKey)
        set[DataModel](generatedKey, twentyHours, f)
    }
  }

  def generateKey(method: String, parameter: String): String = {
    println(method + parameter.toString())
    method + parameter.toString()
  }
}

