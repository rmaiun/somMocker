package dev.rmaiun.sommocker

import cats.effect.kernel.Ref
import cats.effect.{ Async, Resource }
import cats.syntax.all._
import com.comcast.ip4s._
import dev.rmaiun.sommocker.dtos.{ AlgorithmStructure, AlgorithmStructureSet, ConfigurationDataDto, ConfigurationKeyDto }
import .initConnection
import dev.rmaiun.sommocker.services.{ ConfigProvider, RequestProcessor }
import fs2.Stream
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.Logger

object SommockerServer {
  def stream[F[_]: Async]: Stream[F, Nothing] = {
    val appCfg        = ConfigProvider.provideConfig
    val algorithmCode = appCfg.broker.algorithm
    val a1            = algorithmCode.replace("$$$", "1")
    val a2            = algorithmCode.replace("$$$", "2")
    val a3            = algorithmCode.replace("$$$", "3")
    val a4            = algorithmCode.replace("$$$", "4")
    for {
      ref         <- Stream.eval(Ref[F].of(Map[ConfigurationKeyDto, ConfigurationDataDto]()))
      structsAlg1 <- initConnection(RabbitHelper.config(appCfg.broker), a1)
      structsAlg2 <- initConnection(RabbitHelper.config(appCfg.broker), algorithmCode.replace("$$$", a2))
      structsAlg3 <- initConnection(RabbitHelper.config(appCfg.broker), algorithmCode.replace("$$$", a3))
      structsAlg4 <- initConnection(RabbitHelper.config(appCfg.broker), algorithmCode.replace("$$$", a4))
      structs = Set(
                  AlgorithmStructure(a1, structsAlg1),
                  AlgorithmStructure(a2, structsAlg2),
                  AlgorithmStructure(a3, structsAlg3),
                  AlgorithmStructure(a4, structsAlg4)
                )
      processor = RequestProcessor.impl(ref, AlgorithmStructureSet(structs))
      httpApp = (
                  SommockerRoutes.initMock[F](processor) <+>
                    SommockerRoutes.evaluateMock[F](processor)
                ).orNotFound

      // With Middlewares in place
      finalHttpApp = Logger.httpApp(logHeaders = true, logBody = true)(httpApp)
      exitCode <-
        Stream
          .resource(
            EmberServerBuilder
              .default[F]
              .withHost(ipv4"0.0.0.0")
              .withPort(port"8080")
              .withHttpApp(finalHttpApp)
              .build >>
              Resource.eval(Async[F].never)
          )
          .concurrently(structsAlg1.requestConsumer.evalTap(envelope => processor.processIncomingMessage(envelope)))
          .concurrently(structsAlg2.requestConsumer.evalTap(envelope => processor.processIncomingMessage(envelope)))
          .concurrently(structsAlg3.requestConsumer.evalTap(envelope => processor.processIncomingMessage(envelope)))
          .concurrently(structsAlg4.requestConsumer.evalTap(envelope => processor.processIncomingMessage(envelope)))
    } yield exitCode
  }.drain
}
