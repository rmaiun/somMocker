package dev.rmaiun.sommocker.services

import dev.rmaiun.sommocker.dtos._
import io.circe.Json
import zio._

import scala.language.postfixOps

object RequestProcessor {
  val live: ZLayer[Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]] & AlgorithmStructureSet, Nothing, RequestProcessor] =
    ZLayer.fromFunction(new RequestProcessor(_, _))
}

class RequestProcessor(
  stubs: Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]],
  algorithmStructureSet: AlgorithmStructureSet
) {
  // zio logging
//  implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromClass[F](getClass)

  def storeRequestConfiguration(dto: ConfigurationDataDto): Task[ConfigurationKeyDto] =
    for {
      _ <- stubs.update(map => map + (ConfigurationKeyDto(dto.processCode, dto.optimizationRunId) -> dto))
    } yield ConfigurationKeyDto(dto.processCode, dto.optimizationRunId)

  def invokeRequest(dto: ConfigurationKeyDto): Task[Unit] =
    for {
//      _   <- logger.info(s"Processing request for $dto")
      map <- stubs.get
      _   <- sendResults(dto, map)
    } yield ()

  private def sendResults(dto: ConfigurationKeyDto, map: Map[ConfigurationKeyDto, ConfigurationDataDto]): Task[Unit] = {
    import dev.rmaiun.sommocker.dtos.LogDto._
    import io.circe.syntax._
    val unit: Task[Unit] = ZIO.unit
    map.get(dto).fold(unit) { data =>
      val qty      = data.nodesQty
      val messages = (0 until qty).map(_ => data.resultMock.toString()).toList
      val logs = if (data.logsEnabled) {
        (0 until qty).flatMap { _ =>
          val log1 = LogDto("defaultInstanceId", "2022-09-02T14:44:19.172Z", "INFO", "Disaggregation starts with SOM v1.0.2")
          val log2 = LogDto("defaultInstanceId", "2022-09-02T14:44:21.172Z", "INFO", "Instance was allocated")
          val log3 = LogDto("defaultInstanceId", "2022-09-02T14:44:25.172Z", "INFO", "Computation completed")
          List(log1.asJson.toString(), log2.asJson.toString(), log2.asJson.toString())
        }.toList
      } else {
        List.empty
      }
      val headers            = logHeaders(dto)
      val amqpMessagesSender = defineSenderF(data.algorithm, algorithmStructureSet, headers)(_, _)
//      logger.info(s"Delivering ${messages.size} results") *>
//        logger.info(s"Delivering ${logs.size} logs") *>
      amqpMessagesSender(messages, false) *> amqpMessagesSender(logs, true)
    }
  }

  private def defineSenderF(
    algorithm: String,
    algorithmStructureSet: AlgorithmStructureSet,
    headers: Map[String, AnyRef]
  )(amqpMessages: List[String], logs: Boolean): Task[Unit] =
    algorithmStructureSet.structures
      .find(_.code == algorithm)
      .fold(ZIO.unit) { found =>
        val effects = amqpMessages
          .map(m => if (logs) found.structs.logsPublisher(m, headers) else found.structs.resultsPublisher(m, Map.empty))
        ZIO.forkAllDiscard(effects)
      }

  def processIncomingMessage(e: String): Task[Unit] = {
    import dev.rmaiun.sommocker.dtos.ConfigurationKeyDto._
    import io.circe.parser._
    val dto = parse(e).getOrElse(Json.Null).as[ConfigurationKeyDto].getOrElse(ConfigurationKeyDto("-1", "-1"))

    for {
//      _  <- logger.info(s"---> incoming request ${e.payload}")
      _ <- invokeRequest(dto)
    } yield ()

  }

  private def logHeaders(key: ConfigurationKeyDto): Map[String, AnyRef] =
    Map(
      "categoryName"       -> "com.artelys.som.logging.SomLogger",
      "level"              -> "INFO",
      "som.command"        -> "mari",
      "som.optimizationId" -> key.optimizationRunId,
      "som.processId"      -> key.processCode
    )
}
