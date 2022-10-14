package dev.rmaiun.sommocker.services

import cats.Monad
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.{ Async, MonadCancel }
import dev.profunktor.fs2rabbit.arguments.Arguments
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration._
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.ExchangeType.{ Direct, FanOut }
import dev.profunktor.fs2rabbit.model._
import dev.rmaiun.sommocker.dtos.BrokerConfiguration
import fs2.{ Stream => Fs2Stream }

import java.nio.charset.Charset
import scala.concurrent.duration.DurationInt

object RabbitHelper {
  type AmqpPublisher[F[_]]  = AmqpMessage[String] => F[Unit]
  type AmqpConsumer[F[_]]   = Fs2Stream[F, AmqpEnvelope[String]]
  type MonadThrowable[F[_]] = MonadCancel[F, Throwable]

  case class AmqpStructures[F[_]](
    requestPublisher: AmqpPublisher[F],
    requestConsumer: AmqpConsumer[F],
    resultsPublisher: AmqpPublisher[F],
    logsPublisher: AmqpPublisher[F]
  )

  def config(cfg: BrokerConfiguration): Fs2RabbitConfig = Fs2RabbitConfig(
    virtualHost = cfg.vhost,
    host = cfg.host,
    port = cfg.port,
    connectionTimeout = 5000.seconds,
    username = Some(cfg.username),
    password = Some(cfg.password),
    ssl = false,
    requeueOnNack = false,
    requeueOnReject = false,
    internalQueueSize = Some(500),
    automaticRecovery = true
  )

  def initConnection[F[_]: Async](cfg: Fs2RabbitConfig, algorithm: String): Fs2Stream[F, AmqpStructures[F]] =
    for {
      dispatcher <- Fs2Stream.resource(Dispatcher[F])
      rc         <- Fs2Stream.eval(RabbitClient[F](cfg, dispatcher))
      _          <- Fs2Stream.eval(RabbitHelper.initRabbitRoutes(rc, algorithm))
      structs    <- RabbitHelper.createRabbitConnection(rc, algorithm)
    } yield structs

  def initRabbitRoutes[F[_]](rc: RabbitClient[F], algorithm: String)(implicit
    MC: MonadCancel[F, Throwable]
  ): F[Unit] = {
    import cats.implicits._
    val channel                 = rc.createConnectionChannel
    val algRequestQueue         = requestQueue(algorithm)
    val algResultsQueue         = resultsQueue(algorithm)
    val algLogsQueue            = logsQueue(algorithm)
    val algRequestExchange      = requestExchange(algorithm)
    val algResultsInterExchange = resultsInternalExchange(algorithm)
    val algLogsInterExchange    = logsInternalExchange(algorithm)
    channel.use { implicit ch =>
      for {
        _ <- rc.declareQueue(queueCfg(algRequestQueue))
        _ <- rc.declareQueue(queueCfg(algResultsQueue))
        _ <- rc.declareQueue(queueCfg(algLogsQueue))
        _ <- rc.declareExchange(exchangeCfg(algRequestExchange, FanOut))
        _ <- rc.declareExchange(exchangeCfg(algResultsInterExchange, Direct))
        _ <- rc.declareExchange(exchangeCfg(algLogsInterExchange, Direct))
        _ <- rc.bindQueue(algRequestQueue, algRequestExchange, defaultRoutingKey)(ch)
        _ <- rc.bindQueue(algResultsQueue, algResultsInterExchange, defaultRoutingKey)(ch)
        _ <- rc.bindQueue(algLogsQueue, algLogsInterExchange, defaultRoutingKey)(ch)
      } yield ()
    }
  }

  def createRabbitConnection[F[_]](
    rc: RabbitClient[F],
    algorithm: String
  )(implicit MC: MonadCancel[F, Throwable]): Fs2Stream[F, AmqpStructures[F]] = {
    implicit val stringMessageCodec: Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]](s => Monad[F].pure(s.copy(payload = s.payload.getBytes(Charset.defaultCharset()))))
    for {
      requestPublisher <- publisher(requestExchange(algorithm), defaultRoutingKey, rc)
      resultsPublisher <- publisher(resultsInternalExchange(algorithm), defaultRoutingKey, rc)
      logsPublisher    <- publisher(logsInternalExchange(algorithm), defaultRoutingKey, rc)
      requestConsumer  <- autoAckConsumer(requestQueue(algorithm), rc)
    } yield AmqpStructures(requestPublisher, requestConsumer, resultsPublisher, logsPublisher)
  }

  def autoAckConsumer[F[_]: MonadThrowable](
    q: QueueName,
    rc: RabbitClient[F]
  ): Fs2Stream[F, Fs2Stream[F, AmqpEnvelope[String]]] =
    Fs2Stream
      .resource(rc.createConnectionChannel)
      .flatMap(implicit ch => Fs2Stream.eval(rc.createAutoAckConsumer(q)))

  def publisher[F[_]: MonadThrowable](exchangeName: ExchangeName, rk: RoutingKey, rc: RabbitClient[F])(implicit
    me: MessageEncoder[F, AmqpMessage[String]]
  ): Fs2Stream[F, AmqpMessage[String] => F[Unit]] =
    for {
      ch <- Fs2Stream.resource(rc.createConnectionChannel)
      x  <- Fs2Stream.eval(rc.createPublisher[AmqpMessage[String]](exchangeName, rk)(ch, me))
    } yield x

  private def exchangeCfg(name: ExchangeName, exchType: ExchangeType): DeclarationExchangeConfig =
    DeclarationExchangeConfig(name, exchType, NonDurable, NonAutoDelete, NonInternal, Map())

  private def queueCfg(name: QueueName): DeclarationQueueConfig = {
    val uafConfigs: Arguments = Map(
      "x-message-ttl" -> 99999999,
      "x-max-length"  -> 10000,
      "x-overflow"    -> "reject-publish",
      "x-queue-mode"  -> "lazy"
    )
    DeclarationQueueConfig(name, NonDurable, NonExclusive, NonAutoDelete, uafConfigs)
  }

  private def requestQueue(algorithm: String) = QueueName(s"SOM_REQUEST_$algorithm")

  private def resultsQueue(algorithm: String) = QueueName(s"OptimizationResult_$algorithm")

  private def logsQueue(algorithm: String) = QueueName(s"OptimizationStatus_$algorithm")

  private val defaultRoutingKey = RoutingKey("")

  private def requestExchange(algorithm: String) = ExchangeName(s"OptimizationControl_$algorithm")

  private def resultsInternalExchange(algorithm: String) = ExchangeName(s"som_results_exchange_internal_$algorithm")

  private def logsInternalExchange(algorithm: String) = ExchangeName(s"som_logs_exchange_internal_$algorithm")
}
