package dev.rmaiun.sommocker.services
import com.rabbitmq.client.{ AMQP, Connection }
import dev.rmaiun.sommocker.dtos.{ AmqpComponents, BrokerConfiguration }
import nl.vroste.zio.amqp._
import nl.vroste.zio.amqp.model.ExchangeType.{ Direct, Fanout }
import nl.vroste.zio.amqp.model._
import zio._
import zio.stream.ZStream

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
object RabbitInitializer {
  type ZPublisher = (String, Map[String, AnyRef]) => ZIO[Any, Throwable, Unit]
  type ZConsumer  = ZStream[Any, Throwable, String]
  val uafConfigs: Map[String, AnyRef] = Map(
    "x-message-ttl" -> Long.box(99999999),
    "x-max-length"  -> Int.box(10000),
    "x-overflow"    -> "reject-publish",
    "x-queue-mode"  -> "lazy"
  )

  def initRabbit(algorithm: String, cfg: BrokerConfiguration): Unit = {
    val aMQPConfig = AMQPConfig(cfg.username, cfg.password, cfg.vhost, 10 seconds, ssl = false, cfg.host, cfg.port.toShort, 5000 seconds)
    for {
      connection       <- ZStream.acquireReleaseWith(Amqp.connect(aMQPConfig))(_ => ZIO.succeed(()))
      _                <- initRabbitDataStructures(connection, algorithm)
      requestPublisher <- initPublisher(connection, algorithm, requestExchange(algorithm))
      resultsPublisher <- initPublisher(connection, algorithm, resultsInternalExchange(algorithm))
      logsPublisher    <- initPublisher(connection, algorithm, logsInternalExchange(algorithm))
      requestConsumer  <- initRequestConsumer(connection, requestQueue(algorithm))
    } yield AmqpComponents(requestPublisher, requestConsumer, resultsPublisher, logsPublisher)
  }

  private def initPublisher(connection: Connection, algorithm: String, exchange: ExchangeName): ZStream[Scope, Throwable, ZPublisher] = {
    val scoped = ZIO.scoped {
      for {
        c <- Amqp.createChannel(connection)
      } yield { (data: String, headers: Map[String, AnyRef]) =>
        val props = new AMQP.BasicProperties(
          "application/json",
          "UTF-8",
          headers.asJava,
          null,
          null,
          UUID.randomUUID().toString,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null
        )
        c.publish(exchange, data.getBytes(StandardCharsets.UTF_8), defaultRoutingKey, props = props)
      }
    }
    ZStream.acquireReleaseWith(scoped)(_ => ZIO.unit)
  }

  private def initRequestConsumer(connection: Connection, queue: QueueName): ZStream[Scope, Throwable, ZConsumer] = {
    val scoped = ZIO.scoped {
      for {
        c <- Amqp.createChannel(connection)
      } yield c
        .consume(queue, ConsumerTag("requestConsumer"), autoAck = true)
        .map(delivery => new String(delivery.getBody, StandardCharsets.UTF_8))
    }
    ZStream.acquireReleaseWith(scoped)(_ => ZIO.unit)
  }

  private def initRabbitDataStructures(connection: Connection, algorithm: String): ZStream[Any, Throwable, Unit] = {
    val algRequestQueue         = requestQueue(algorithm)
    val algResultsQueue         = resultsQueue(algorithm)
    val algLogsQueue            = logsQueue(algorithm)
    val algRequestExchange      = requestExchange(algorithm)
    val algResultsInterExchange = resultsInternalExchange(algorithm)
    val algLogsInterExchange    = logsInternalExchange(algorithm)
    val scoped = ZIO.scoped {
      for {
        c <- Amqp.createChannel(connection)
        _ <- c.queueDeclare(algRequestQueue, durable = false, autoDelete = false, exclusive = false, arguments = uafConfigs)
        _ <- c.queueDeclare(algResultsQueue, durable = false, autoDelete = false, exclusive = false, arguments = uafConfigs)
        _ <- c.queueDeclare(algLogsQueue, durable = false, autoDelete = false, exclusive = false, arguments = uafConfigs)
        _ <- c.exchangeDeclare(algRequestExchange, Fanout, durable = false, autoDelete = false, internal = false)
        _ <- c.exchangeDeclare(algResultsInterExchange, Direct, durable = false, autoDelete = false, internal = false)
        _ <- c.exchangeDeclare(algLogsInterExchange, Direct, durable = false, autoDelete = false, internal = false)
        _ <- c.queueBind(algRequestQueue, algRequestExchange, defaultRoutingKey)
        _ <- c.queueBind(algResultsQueue, algResultsInterExchange, defaultRoutingKey)
        _ <- c.queueBind(algLogsQueue, algLogsInterExchange, defaultRoutingKey)
      } yield ()
    }
    ZStream.acquireReleaseWith(scoped)(_ => ZIO.succeed(()))
  }

  private def requestQueue(algorithm: String) = QueueName(s"SOM_REQUEST_$algorithm")

  private def resultsQueue(algorithm: String) = QueueName(s"OptimizationResult_$algorithm")

  private def logsQueue(algorithm: String) = QueueName(s"OptimizationStatus_$algorithm")

  private val defaultRoutingKey = RoutingKey("")

  private def requestExchange(algorithm: String) = ExchangeName(s"OptimizationControl_$algorithm")

  private def resultsInternalExchange(algorithm: String) = ExchangeName(s"som_results_exchange_internal_$algorithm")

  private def logsInternalExchange(algorithm: String) = ExchangeName(s"som_logs_exchange_internal_$algorithm")
}
