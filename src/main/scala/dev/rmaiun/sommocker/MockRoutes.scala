package dev.rmaiun.sommocker

import dev.rmaiun.sommocker.dtos.ErrorInfo
import dev.rmaiun.sommocker.services.RequestProcessor
import org.http4s.HttpRoutes
import sttp.tapir.server.http4s.ztapir.ZHttp4sServerInterpreter
import sttp.tapir.ztapir._
import zio.{ RIO, ZIO }

class MockRoutes[R <: RequestProcessor] {
  import Endpoints._
  private val routes: HttpRoutes[ZIO[R, Throwable, *]] = {
    val initMockServerEndpoint: ZServerEndpoint[RequestProcessor, Any] = initMockEndpoint.zServerLogic { dto =>
      val result = for {
        rp  <- ZIO.service[RequestProcessor]
        out <- rp.storeRequestConfiguration(dto)
      } yield out
      result.mapError(err => ErrorInfo(err.getMessage))
    }

    val evaluateMockServerEndpoint: ZServerEndpoint[RequestProcessor, Any] = evaluateMockEndpoint.zServerLogic { dto =>
      val result = for {
        rp  <- ZIO.service[RequestProcessor]
        out <- rp.invokeRequest(dto)
      } yield out
      result.mapError(err => ErrorInfo(err.getMessage))
    }

    val listMockServerEndpoint: ZServerEndpoint[RequestProcessor, Any] = listMockEndpoint.zServerLogic { _ =>
      val result = for {
        rp  <- ZIO.service[RequestProcessor]
        out <- rp.listMocks
      } yield out
      result.mapError(err => ErrorInfo(err.getMessage))
    }

    ZHttp4sServerInterpreter[R]()
      .from(
        List(
          initMockServerEndpoint.widen[R],
          evaluateMockServerEndpoint.widen[R],
          listMockServerEndpoint.widen[R]
        )
      )
      .toRoutes
  }
}
