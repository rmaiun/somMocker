package dev.rmaiun.sommocker.services

import cats.effect.{ Ref, Sync }
import cats.implicits._
import cats.{ Applicative, Monad }
import dev.profunktor.fs2rabbit.model.AmqpFieldValue.StringVal
import dev.profunktor.fs2rabbit.model.{ AmqpEnvelope, AmqpFieldValue, AmqpMessage, AmqpProperties }
import dev.rmaiun.sommocker.dtos._
import io.circe.Json
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
object RequestProcessor {
  def apply[F[_]](implicit ev: RequestProcessor[F]): RequestProcessor[F] = ev

  def impl[F[_]: Sync](
    stubs: Ref[F, Map[ConfigurationKeyDto, ConfigurationDataDto]],
    algorithmStructureSet: AlgorithmStructureSet[F]
  ): RequestProcessor[F] =
    new RequestProcessor[F](stubs, algorithmStructureSet)
}

class RequestProcessor[F[_]: Sync](
  stubs: Ref[F, Map[ConfigurationKeyDto, ConfigurationDataDto]],
  algorithmStructureSet: AlgorithmStructureSet[F]
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
          val log1                                          = LogDto("defaultInstanceId", "2022-09-02T14:44:19.172Z", "INFO", "Disaggregation starts with SOM v1.0.2")
          val log2                                          = LogDto("defaultInstanceId", "2022-09-02T14:44:21.172Z", "INFO", "Instance was allocated")
          val log3                                          = LogDto("defaultInstanceId", "2022-09-02T14:44:25.172Z", "INFO", "Computation completed")
          implicit val headers: Map[String, AmqpFieldValue] = logHeaders(dto)
          List(amqpMsg(log1.asJson.toString()), amqpMsg(log2.asJson.toString()), amqpMsg(log3.asJson.toString()))
        }.toList
      } else {
        List.empty
      }
      val amqpMessagesSender = sendAmqpMessages(data.algorithm, algorithmStructureSet)(_, _)
      logger.info(s"Delivering ${messages.size} results") *>
        logger.info(s"Delivering ${logs.size} logs") *>
        amqpMessagesSender(messages, false) *> amqpMessagesSender(logs, true)
    }
  }

  private def sendAmqpMessages(
    algorithm: String,
    algorithmStructureSet: AlgorithmStructureSet[F]
  )(amqpMessages: List[AmqpMessage[String]], logs: Boolean): F[Unit] =
    algorithmStructureSet.structures
      .find(_.code == algorithm)
      .fold(Applicative[F].unit)(found => amqpMessages.map(m => if (logs) found.structs.logsPublisher(m) else found.structs.resultsPublisher(m)).sequence_)

  def processIncomingMessage(e: AmqpEnvelope[String]): F[Unit] = {
    import dev.rmaiun.sommocker.dtos.ConfigurationKeyDto._
    import io.circe.parser._
    for {
      _  <- logger.info(s"---> incoming request ${e.payload}")
      dto = parse(e.payload).getOrElse(Json.Null).as[ConfigurationKeyDto].getOrElse(ConfigurationKeyDto("-1", "-1"))
      _  <- invokeRequest(dto)
    } yield ()

  }

  private def logHeaders(key: ConfigurationKeyDto): Map[String, AmqpFieldValue] =
    Map(
      "categoryName"       -> StringVal("com.artelys.som.logging.SomLogger"),
      "level"              -> StringVal("INFO"),
      "som.command"        -> StringVal("mari"),
      "som.optimizationId" -> StringVal(key.optimizationRunId),
      "som.processId"      -> StringVal(key.processCode)
    )

  private def amqpMsg(data: String)(implicit headers: Map[String, AmqpFieldValue] = Map.empty): AmqpMessage[String] =
    AmqpMessage(
      data,
      AmqpProperties(headers = headers, contentType = Some("application/json"), contentEncoding = Some("UTF-8"))
    )
}
