package dev.rmaiun.sommocker.services

import dev.rmaiun.sommocker.dtos._
import io.circe.Json
import zio._

import scala.language.postfixOps

object RequestProcessor {
  type RequestProcessorEnv = Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]] & AlgorithmStructureSet

  val live: ZLayer[RequestProcessorEnv, Nothing, RequestProcessor] =
    ZLayer.fromFunction(new RequestProcessor(_, _))
}

class RequestProcessor(
  stubs: Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]],
  algorithmStructureSet: AlgorithmStructureSet
) {
  def storeRequestConfiguration(dto: ConfigurationDataDto): Task[ConfigurationKeyDto] =
    for {
      _ <- stubs.update(map => map + (ConfigurationKeyDto(dto.processId, dto.optimizationId) -> dto))
    } yield ConfigurationKeyDto(dto.processId, dto.optimizationId)

  def invokeRequest(dto: ConfigurationKeyDto, semiAutoMode: Boolean = false, duration: Duration = 1 seconds): Task[EmptyResult] =
    for {
      _   <- ZIO.logInfo(s"Processing request for $dto")
      map <- stubs.get
      _   <- sendResults(dto, map, duration, semiAutoMode = semiAutoMode)
    } yield EmptyResult()

  private def sendResults(
    dto: ConfigurationKeyDto,
    map: Map[ConfigurationKeyDto, ConfigurationDataDto],
    duration: Duration,
    semiAutoMode: Boolean = false
  ): Task[Unit] = {
    import dev.rmaiun.sommocker.dtos.LogDto._
    import io.circe.syntax._
    val unit: Task[Unit] = ZIO.unit
    val key              = if (semiAutoMode) ConfigurationKeyDto(dto.processId, "*") else dto
    map.get(key).fold(unit) { data =>
      val qty = data.nodesQty
      val messages = (0 until qty).map { _ =>
        val updFields = Json.fromFields(Seq(("processId", Json.fromString(dto.processId)), ("optimizationId", Json.fromString(dto.optimizationId))))
        data.resultMock.deepMerge(updFields).toString()
      }.toList
      val logs = if (data.logsEnabled) {
        (0 until qty).flatMap { _ =>
          val log1 = LogDto("defaultInstanceId", "2022-09-02T14:44:19.172Z", "INFO", "Disaggregation starts with SOM v1.0.2")
          val log2 = LogDto("defaultInstanceId", "2022-09-02T14:44:21.172Z", "INFO", "Instance was allocated")
          val log3 = LogDto("defaultInstanceId", "2022-09-02T14:44:25.172Z", "INFO", "Computation completed")
          List(log1.asJson.toString(), log2.asJson.toString(), log3.asJson.toString())
        }.toList
      } else {
        List.empty
      }
      val headers            = logHeaders(dto)
      val amqpMessagesSender = defineSenderF(data.algorithm, algorithmStructureSet, headers)(_, _)
      for {
        _ <- ZIO.logInfo("Delivery is planned")
        _ <- ZIO.sleep(duration)
        _ <- ZIO.logInfo(s"Delivering ${messages.size} results and ${logs.size} logs")
        _ <- amqpMessagesSender(messages, false) *> amqpMessagesSender(logs, true)
        _ <- ZIO.logInfo("Delivery is successfully finished")
      } yield ()
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

    val dto = for {
      json <- parse(e)
      obj  <- json.as[ConfigurationKeyDto]
    } yield obj

    val finalDto = dto.getOrElse(ConfigurationKeyDto("-1", "-1"))

    for {
      _ <- ZIO.logInfo(s"---> incoming request $e")
      _ <- invokeRequest(finalDto, semiAutoMode = true, 15 seconds)
    } yield ()

  }

  private def logHeaders(key: ConfigurationKeyDto): Map[String, AnyRef] =
    Map(
      "categoryName"       -> "com.artelys.som.logging.SomLogger",
      "level"              -> "INFO",
      "som.command"        -> "mari",
      "som.optimizationId" -> key.optimizationId,
      "som.processId"      -> key.processId
    )
}
