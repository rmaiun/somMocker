package dev.rmaiun.sommocker.services

import cats.Monad
import cats.effect.{Ref, Sync}
import cats.implicits._
import dev.profunktor.fs2rabbit.model.{AmqpEnvelope, AmqpMessage, AmqpProperties}
import dev.rmaiun.sommocker.dtos._
import dev.rmaiun.sommocker.services.RabbitHelper.{AmqpPublisher, AmqpStructures}
import io.circe.Json
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
object RequestProcessor {
  def apply[F[_]](implicit ev: RequestProcessor[F]): RequestProcessor[F] = ev

  def impl[F[_]: Sync](
    stubs: Ref[F, Map[ConfigurationKeyDto, ConfigurationDataDto]],
    structs: AmqpStructures[F]
  ): RequestProcessor[F] =
    new RequestProcessor[F](stubs, structs.resultsPublisher, structs.logsPublisher)
}

class RequestProcessor[F[_]: Sync](
  stubs: Ref[F, Map[ConfigurationKeyDto, ConfigurationDataDto]],
  resultsPublisher: AmqpPublisher[F],
  logsPublisher: AmqpPublisher[F]
) {
  implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)

  def storeRequestConfiguration(dto: ConfigurationDataDto): F[ConfigurationKeyDto] =
    for {
      _ <- stubs.update(map => map + (ConfigurationKeyDto(dto.processCode, dto.optimizationRunId) -> dto))
    } yield ConfigurationKeyDto(dto.processCode, dto.optimizationRunId)

  def invokeRequest(dto: ConfigurationKeyDto): F[Unit] =
    for {
      _   <- logger.info(s"Processing request for $dto")
      map <- stubs.get
      _   <- sendResults(dto, map)
    } yield ()

  private def sendResults(dto: ConfigurationKeyDto, map: Map[ConfigurationKeyDto, ConfigurationDataDto]): F[Unit] = {
    import dev.rmaiun.sommocker.dtos.LogDto._
    import io.circe.syntax._
    map.get(dto).fold(Monad[F].unit) { data =>
      val qty      = data.nodesQty
      val messages = (0 until qty).map(_ => amqpMsg(data.resultMock.toString())).toList
      val logs = if (data.logsEnabled) {
        (0 until qty).flatMap { _ =>
          val log1 =
            LogDto("defaultInstanceId", "2022-09-02T14:44:19.172Z", "INFO", "Disaggregation starts with SOM v1.0.2")
          val log2 = LogDto("defaultInstanceId", "2022-09-02T14:44:21.172Z", "INFO", "Instance was allocated")
          val log3 = LogDto("defaultInstanceId", "2022-09-02T14:44:25.172Z", "INFO", "Computation completed")

          List(amqpMsg(log1.asJson.toString()), amqpMsg(log2.asJson.toString()), amqpMsg(log3.asJson.toString()))
        }.toList
      } else {
        List()
      }
      logger.info(s"Delivering ${messages.size} results") *>
        logger.info(s"Delivering ${logs.size} logs") *>
        messages.map(m => resultsPublisher(m)).sequence_ *> logs.map(l => logsPublisher(l)).sequence_
    }
  }

  def processIncomingMessage(e: AmqpEnvelope[String]): F[Unit] = {
    import dev.rmaiun.sommocker.dtos.ConfigurationKeyDto._
    import io.circe.parser._
    for {
      _  <- logger.info(s"---> incoming request ${e.payload}")
      dto = parse(e.payload).getOrElse(Json.Null).as[ConfigurationKeyDto].getOrElse(ConfigurationKeyDto("-1", "-1"))
      _  <- invokeRequest(dto)
    } yield ()

  }

  private def amqpMsg(data: String): AmqpMessage[String] = AmqpMessage(data, AmqpProperties())
}
