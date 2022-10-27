package dev.rmaiun.sommocker

import dev.rmaiun.sommocker.Server.AppEnv
import dev.rmaiun.sommocker.dtos.{ AlgorithmStructure, AlgorithmStructureSet, ConfigurationDataDto, ConfigurationKeyDto }
import dev.rmaiun.sommocker.services.{ ConfigProvider, RabbitInitializer, RequestProcessor }
import zio._
import zio.logging.backend._

import scala.language.postfixOps

object Boot extends ZIOApp {

  private val logger = Runtime.removeDefaultLoggers >>> SLF4J.slf4j

  override implicit def environmentTag: zio.EnvironmentTag[Environment] = EnvironmentTag[Environment]

  override type Environment = AppEnv

  val refLayer: ZLayer[Scope, Nothing, Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]]] =
    ZLayer.fromZIO(
      ZIO.logInfo(s"---> Creating refLayer") *>
        Ref.make(Map[ConfigurationKeyDto, ConfigurationDataDto]())
    )

  val structsLayer: ZLayer[Scope, Throwable, AlgorithmStructureSet] = {
    ZLayer.fromZIO {
      val appCfg        = ConfigProvider.provideConfig
      val algorithmCode = appCfg.broker.algorithm
      val a1            = algorithmCode.replace("$$$", "1")
      val a2            = algorithmCode.replace("$$$", "2")
      val a3            = algorithmCode.replace("$$$", "3")
      val a4            = algorithmCode.replace("$$$", "4")
      val effect = for {
        _           <- ZIO.logInfo(s"---> Creating structsLayer")
        structsAlg1 <- RabbitInitializer.initRabbit(a1, appCfg.broker)
        structsAlg2 <- RabbitInitializer.initRabbit(a2, appCfg.broker)
        structsAlg3 <- RabbitInitializer.initRabbit(a3, appCfg.broker)
        structsAlg4 <- RabbitInitializer.initRabbit(a4, appCfg.broker)
      } yield AlgorithmStructureSet(
        Set(
          AlgorithmStructure(a1, structsAlg1),
          AlgorithmStructure(a2, structsAlg2),
          AlgorithmStructure(a3, structsAlg3),
          AlgorithmStructure(a4, structsAlg4)
        )
      )
      for {
        structs <- effect.tapErrorCause(err => ZIO.logCause(Cause.fail(err)))
        _       <- ZIO.logInfo(s"---> Structs were successfully created")
      } yield structs
    }
  }

  override def bootstrap: ZLayer[ZIOAppArgs, Any, Environment] = {
    val finalLayer = ZLayer
      .make[Environment](
        Scope.default,
        refLayer,
        structsLayer,
        RequestProcessor.live
      ) ++ logger
    finalLayer.tapErrorCause(err => ZIO.logCause(Cause.fail(err)))
  }

  override def run: ZIO[Environment & ZIOAppArgs, Any, Any] =
    ZIO.logInfo(s"---> Starting server") *>
      Server
        .start()
        .tapErrorCause(err => ZIO.logCause(Cause.fail(err)))
        .exitCode
}
