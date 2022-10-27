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
import sttp.tapir.server.http4s.ztapir.ZHttp4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import zio.interop.catz._
import zio.{ RIO, _ }

object Server {
  type MockRef = Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]]
  type AppEnv  = RequestProcessor with AlgorithmStructureSet with MockRef with Scope
  def run(): ZIO[AppEnv, Throwable, Unit] = {
    // docs/index.html?url=/docs/docs.yml
    val swaggerRoutes = ZHttp4sServerInterpreter()
      .from(SwaggerInterpreter().fromServerEndpoints[RIO[AppEnv, *]](Endpoints.endpoints, "sommocker", "0.1.0"))
      .toRoutes
    val httpApp      = Router("/" -> (Endpoints.routes <+> swaggerRoutes)).orNotFound
    val finalHttpApp = Logger.httpApp(logHeaders = true, logBody = false)(httpApp)
    for {
      _   <- ZIO.logInfo("Starting server ...")
      set <- ZIO.service[AlgorithmStructureSet]
      rp  <- ZIO.service[RequestProcessor]
      _   <- (ZIO foreach set.structures)(s => s.structs.requestConsumer.tap(str => rp.processIncomingMessage(str)).runDrain.fork)
      scoped <- (EmberServerBuilder
                  .default[RIO[AppEnv, *]]
                  .withHost(ipv4"0.0.0.0")
                  .withPort(port"8080")
                  .withHttpApp(finalHttpApp)
                  .build >> Resource.eval(Async[RIO[AppEnv, *]].never)).toScopedZIO
    } yield scoped
  }
}
