package dev.rmaiun.sommocker

import cats.effect.kernel.Ref
import cats.effect.{ Async, Resource }
import cats.syntax.all._
import com.comcast.ip4s._
import dev.rmaiun.sommocker.dtos.{ ConfigurationDataDto, ConfigurationKeyDto }
import dev.rmaiun.sommocker.services.RabbitHelper.initConnection
import dev.rmaiun.sommocker.services.{ ConfigProvider, RabbitHelper, RequestProcessor }
import fs2.Stream
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.Logger

object SommockerServer {
  def stream[F[_]: Async]: Stream[F, Nothing] = {
    for {
      ref      <- Stream.eval(Ref[F].of(Map[ConfigurationKeyDto, ConfigurationDataDto]()))
      appCfg    = ConfigProvider.provideConfig
      structs  <- initConnection(RabbitHelper.config(appCfg.broker))
      processor = RequestProcessor.impl(ref, structs)
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
          .concurrently(structs.requestConsumer.evalTap(envelope => processor.processIncomingMessage(envelope)))
    } yield exitCode
  }.drain
}
