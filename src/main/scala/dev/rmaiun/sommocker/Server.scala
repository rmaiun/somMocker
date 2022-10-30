package dev.rmaiun.sommocker

import cats.effect.{ Async, Resource }
import cats.syntax.all._
import com.comcast.ip4s._
import dev.rmaiun.sommocker.dtos.{ AlgorithmStructureSet, ConfigurationDataDto, ConfigurationKeyDto }
import dev.rmaiun.sommocker.services.RequestProcessor
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
import zio.interop.catz._
import zio.{ RIO, _ }

object Server {
  type MockRef = Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]]
  type AppEnv  = RequestProcessor with AlgorithmStructureSet with MockRef with Scope
  def start(): ZIO[AppEnv, Throwable, Unit] =
    for {
      set         <- ZIO.service[AlgorithmStructureSet]
      rp          <- ZIO.service[RequestProcessor]
      _           <- ZIO.logInfo("Starting request consumer ...")
      _           <- (ZIO foreach set.structures)(s => s.structs.requestConsumer.tap(str => rp.processIncomingMessage(str)).runDrain.fork)
      _           <- ZIO.logInfo("request consumer is successfully started")
      httpApp      = Router("/" -> (Endpoints.routes <+> Endpoints.swaggerRoutes)).orNotFound
      finalHttpApp = Logger.httpApp(logHeaders = true, logBody = false)(httpApp)
      scoped <- (EmberServerBuilder
                  .default[RIO[AppEnv, *]]
                  .withHost(ipv4"0.0.0.0")
                  .withPort(port"8080")
                  .withHttpApp(finalHttpApp)
                  .build >> Resource.eval(Async[RIO[AppEnv, *]].never)).toScopedZIO
                  .tapErrorCause(err => ZIO.logCause(Cause.fail(err)))
    } yield scoped
}
