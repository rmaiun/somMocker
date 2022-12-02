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

  def initRabbit(algorithm: String, cfg: BrokerConfiguration): ZIO[Scope, Throwable, AmqpComponents] = {
    val aMQPConfig = AMQPConfig(cfg.username, cfg.password, cfg.vhost, 10 seconds, ssl = false, cfg.host, cfg.port.toShort, 5 seconds)
    for {
      _                <- ZIO.logInfo(s"Establishing connection to RabbitMQ for SOM $algorithm")
      connection       <- Amqp.connect(aMQPConfig)
      _                <- ZIO.logInfo(s"Initializing data structures for SOM $algorithm")
      _                <- initRabbitDataStructures(connection, algorithm)
      _                <- ZIO.logInfo(s"Creating request publisher for SOM $algorithm")
      requestPublisher <- initPublisher(connection, requestExchange(algorithm))
      _                <- ZIO.logInfo(s"Creating results publisher for SOM $algorithm")
      resultsPublisher <- initPublisher(connection, resultsInternalExchange(algorithm))
      _                <- ZIO.logInfo(s"Creating logs publisher for SOM $algorithm")
      logsPublisher    <- initPublisher(connection, logsInternalExchange(algorithm))
      _                <- ZIO.logInfo(s"Creating request consumer for SOM $algorithm")
      requestConsumer  <- initRequestConsumer(connection, requestQueue(algorithm))
    } yield AmqpComponents(requestPublisher, requestConsumer, resultsPublisher, logsPublisher)
  }

  private def initPublisher(connection: Connection, exchange: ExchangeName): ZIO[Scope, Throwable, ZPublisher] =
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

  private def initRequestConsumer(connection: Connection, queue: QueueName): ZIO[Scope, Throwable, ZConsumer] =
    for {
      c <- Amqp.createChannel(connection)
    } yield c
      .consume(queue, ConsumerTag("requestConsumer"), autoAck = true)
      .map(delivery => new String(delivery.getBody, StandardCharsets.UTF_8))

  private def initRabbitDataStructures(connection: Connection, algorithm: String): ZIO[Scope, Throwable, Unit] = {
    val algRequestQueue         = requestQueue(algorithm)
    val algResultsQueue         = resultsQueue(algorithm)
    val algLogsQueue            = logsQueue(algorithm)
    val algRequestExchange      = requestExchange(algorithm)
    val algResultsInterExchange = resultsInternalExchange(algorithm)
    val algLogsInterExchange    = logsInternalExchange(algorithm)
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

  private def requestQueue(algorithm: String) = QueueName(s"OptimizationRequest_$algorithm")

  private def resultsQueue(algorithm: String) = QueueName(s"OptimizationResult_$algorithm")

  private def logsQueue(algorithm: String) = QueueName(s"OptimizationLog_$algorithm")

  private val defaultRoutingKey = RoutingKey("")

  private def requestExchange(algorithm: String) = ExchangeName(s"OptimizationControl_$algorithm")

  private def resultsInternalExchange(algorithm: String) = ExchangeName(s"som_results_exchange_internal_$algorithm")

  private def logsInternalExchange(algorithm: String) = ExchangeName(s"som_logs_exchange_internal_$algorithm")
}
