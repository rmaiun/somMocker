package dev.rmaiun.sommocker

import cats.effect.{ Async, Resource }
import cats.syntax.all._
import com.comcast.ip4s._
import dev.rmaiun.sommocker.Server.AppEnv
import dev.rmaiun.sommocker.dtos.{ AlgorithmStructureSet, ConfigurationDataDto, ConfigurationKeyDto }
import dev.rmaiun.sommocker.services.RequestProcessor
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.middleware.Logger
import zio.{ RIO, _ }
import zio.interop.catz._
import zio.stream.interop.fs2z._

object Server {
  type MockRef = Ref[Map[ConfigurationKeyDto, ConfigurationDataDto]]
  type AppEnv  = RequestProcessor with AlgorithmStructureSet with MockRef with Scope
  def run(): ZIO[AppEnv, Throwable, Unit] = {
    val httpApp      = Router("/" -> Endpoints.routes).orNotFound
    val finalHttpApp = Logger.httpApp(logHeaders = true, logBody = true)(httpApp)
    for {
      scoped <- (EmberServerBuilder
                  .default[RIO[AppEnv, *]]
                  .withHost(ipv4"0.0.0.0")
                  .withPort(port"8080")
                  .withHttpApp(finalHttpApp)
                  .build >> Resource.eval(Async[RIO[AppEnv, *]].never)).toScopedZIO
    } yield scoped
  }

}
