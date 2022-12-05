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
  def listMocks: Task[AllMocks] = stubs.get.map(data => AllMocks(data.values.toList))

  def storeRequestConfiguration(dto: ConfigurationDataDto): Task[ConfigurationKeyDto] =
    for {
      _ <- stubs.update(map => map + (ConfigurationKeyDto(dto.algorithm, dto.command) -> dto))
    } yield ConfigurationKeyDto(dto.algorithm, dto.command)

  def invokeRequest(dto: ConfigurationKeyDto, duration: Duration = 1 seconds, replacement: Option[(String, String)] = None): Task[EmptyResult] =
    for {
      _                          <- ZIO.logInfo(s"Processing request for $dto")
      map                        <- stubs.get
      (processId, optimizationId) = replacement.fold(("*", "*"))(x => x)
      _                          <- sendResults(dto, map, duration, processId, optimizationId)
    } yield EmptyResult()

  private def sendResults(
    key: ConfigurationKeyDto,
    map: Map[ConfigurationKeyDto, ConfigurationDataDto],
    duration: Duration,
    processId: String,
    optimizationId: String
  ): Task[Unit] = {
    import dev.rmaiun.sommocker.dtos.LogDto._
    import io.circe.syntax._
    val unit: Task[Unit] = ZIO.unit
    map.get(key).fold(unit) { data =>
      val mockWithProcess = data.resultMock.hcursor.downField("processId").withFocus(_.mapString(_ => processId)).top.getOrElse(Json.Null)
      val mockWithBothIds = mockWithProcess.hcursor.downField("optimizationId").withFocus(_.mapString(_ => optimizationId)).top.getOrElse(Json.Null)
      val updMock         = mockWithBothIds.toString()
      val qty             = data.nodesQty
      val messages        = (0 until qty).map(_ => updMock).toList
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
      for {
        _                 <- ZIO.logInfo(s"Delivery is scheduled for algorithm ${key.algorithm} and command ${key.command}")
        _                 <- ZIO.sleep(duration)
        _                 <- ZIO.logInfo(s"Example of Response: $updMock")
        _                 <- ZIO.logInfo(s"Delivering ${messages.size} results")
        _                 <- ZIO.logInfo(s"Delivering ${logs.size} logs")
        headers            = logHeaders(optimizationId, processId, key.command)
        amqpMessagesSender = defineSenderF(data.algorithm, algorithmStructureSet, headers)(_, _)
        _                 <- amqpMessagesSender(messages, false) *> amqpMessagesSender(logs, true)
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

  def processIncomingMessage(algorithm: String, e: String): Task[Unit] = {
    import io.circe.parser._
    for {
      _              <- ZIO.logInfo(s"---> incoming request $e")
      command        <- ZIO.fromEither(parse(e).getOrElse(Json.Null).hcursor.downField("command").as[String])
      processId      <- ZIO.fromEither(parse(e).getOrElse(Json.Null).hcursor.downField("processId").as[String])
      optimizationId <- ZIO.fromEither(parse(e).getOrElse(Json.Null).hcursor.downField("optimizationId").as[String])
      _              <- invokeRequest(ConfigurationKeyDto(algorithm, command), 15 seconds, Some((processId, optimizationId)))
    } yield ()

  }

  private def logHeaders(optimizationId: String, processId: String, cmd: String): Map[String, AnyRef] =
    Map(
      "categoryName"       -> "com.artelys.som.logging.SomLogger",
      "level"              -> "INFO",
      "som.command"        -> cmd,
      "som.optimizationId" -> optimizationId,
      "som.processId"      -> processId
    )
}
