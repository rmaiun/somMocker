package dev.rmaiun.sommocker.services

import cats.Monad
import cats.data.Kleisli
import cats.effect.std.Dispatcher
import cats.effect.{Async, MonadCancel}
import dev.profunktor.fs2rabbit.config.Fs2RabbitConfig
import dev.profunktor.fs2rabbit.config.declaration._
import dev.profunktor.fs2rabbit.effects.MessageEncoder
import dev.profunktor.fs2rabbit.interpreter.RabbitClient
import dev.profunktor.fs2rabbit.model.ExchangeType.{Direct, FanOut}
import dev.profunktor.fs2rabbit.model._
import dev.rmaiun.sommocker.dtos.BrokerConfiguration
import fs2.{Stream => Fs2Stream}

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

  def initConnection[F[_]: Async](cfg: Fs2RabbitConfig): Fs2Stream[F, AmqpStructures[F]] =
    for {
      dispatcher <- Fs2Stream.resource(Dispatcher[F])
      rc         <- Fs2Stream.eval(RabbitClient[F](cfg, dispatcher))
      _          <- Fs2Stream.eval(RabbitHelper.initRabbitRoutes(rc))
      structs    <- RabbitHelper.createRabbitConnection(rc)
    } yield structs

  private val requestQueue            = QueueName("SOM_REQUEST")
  private val resultsQueue            = QueueName("OptimizationResult_ALG_SOM1_DEV")
  private val logsQueue               = QueueName("OptimizationStatus_ALG_SOM1_DEV")
  private val defaultRoutingKey       = RoutingKey("")
  private val requestExchange         = ExchangeName("OptimizationControl_ALG_SOM1_DEV")
  private val resultsInternalExchange = ExchangeName("som_results_exchange_internal")
  private val logsInternalExchange    = ExchangeName("som_logs_exchange_internal")

  def initRabbitRoutes[F[_]](rc: RabbitClient[F])(implicit MC: MonadCancel[F, Throwable]): F[Unit] = {
    import cats.implicits._
    val channel = rc.createConnectionChannel
    channel.use { implicit ch =>
      for {
        _ <- rc.declareQueue(DeclarationQueueConfig(requestQueue, NonDurable, NonExclusive, AutoDelete, Map()))
        _ <- rc.declareQueue(DeclarationQueueConfig(resultsQueue, NonDurable, NonExclusive, AutoDelete, Map()))
        _ <- rc.declareQueue(DeclarationQueueConfig(logsQueue, NonDurable, NonExclusive, AutoDelete, Map()))
        _ <-
          rc.declareExchange(
            DeclarationExchangeConfig(requestExchange, FanOut, NonDurable, AutoDelete, NonInternal, Map())
          )
        _ <-
          rc.declareExchange(
            DeclarationExchangeConfig(resultsInternalExchange, Direct, NonDurable, AutoDelete, NonInternal, Map())
          )
        _ <-
          rc.declareExchange(
            DeclarationExchangeConfig(logsInternalExchange, Direct, NonDurable, AutoDelete, NonInternal, Map())
          )
        _ <- rc.bindQueue(requestQueue, requestExchange, defaultRoutingKey)(ch)
        _ <- rc.bindQueue(resultsQueue, resultsInternalExchange, defaultRoutingKey)(ch)
        _ <- rc.bindQueue(logsQueue, logsInternalExchange, defaultRoutingKey)(ch)
      } yield ()
    }
  }

  def createRabbitConnection[F[_]](
    rc: RabbitClient[F]
  )(implicit MC: MonadCancel[F, Throwable]): Fs2Stream[F, AmqpStructures[F]] = {
    implicit val stringMessageCodec: Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]] =
      Kleisli[F, AmqpMessage[String], AmqpMessage[Array[Byte]]](s =>
        Monad[F].pure(s.copy(payload = s.payload.getBytes(Charset.defaultCharset())))
      )
    for {
      requestPublisher <- publisher(requestExchange, defaultRoutingKey, rc)
      resultsPublisher <- publisher(resultsInternalExchange, defaultRoutingKey, rc)
      logsPublisher    <- publisher(logsInternalExchange, defaultRoutingKey, rc)
      requestConsumer  <- autoAckConsumer(requestQueue, rc)
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
}
