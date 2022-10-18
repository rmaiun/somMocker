package dev.rmaiun.sommocker

import dev.rmaiun.sommocker.Server.AppEnv
import dev.rmaiun.sommocker.dtos.ConfigurationDataDto._
import dev.rmaiun.sommocker.dtos.ConfigurationKeyDto._
import dev.rmaiun.sommocker.dtos.{ ConfigurationDataDto, ConfigurationKeyDto, EmptyResult, ErrorInfo }
import dev.rmaiun.sommocker.services.RequestProcessor
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import sttp.tapir.PublicEndpoint
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.ztapir.ZHttp4sServerInterpreter
import sttp.tapir.ztapir._
import zio.{ RIO, ZIO }
object Endpoints {
//  implicit val x1 = stringJsonBody.schema(implicitly[Schema[ConfigurationDataDto]].as[String])
//  implicit val x2 = stringJsonBody.schema(implicitly[Schema[ConfigurationKeyDto]].as[String])
//  implicit val x3  = stringJsonBody.schema(implicitly[Schema[ConfigurationKeyDto]].as[String])
//  implicit val x4  = stringJsonBody.schema(implicitly[Schema[ConfigurationKeyDto]].as[String])

  val initMockEndpoint: PublicEndpoint[ConfigurationDataDto, ErrorInfo, ConfigurationKeyDto, Any] = {
    endpoint.post
      .in("initMock")
      .in(jsonBody[ConfigurationDataDto])
      .out(jsonBody[ConfigurationKeyDto])
      .errorOut(jsonBody[ErrorInfo])
  }

  val evaluateMockEndpoint: PublicEndpoint[ConfigurationKeyDto, ErrorInfo, EmptyResult, Any] = {
    endpoint.post
      .in("evaluateMock")
      .in(jsonBody[ConfigurationKeyDto])
      .out(jsonBody[EmptyResult])
      .errorOut(jsonBody[ErrorInfo])
  }

  def initMockServerEndpoint: ZServerEndpoint[AppEnv, Any] =
    initMockEndpoint.zServerLogic { dto =>
      ZIO
        .serviceWithZIO[services.RequestProcessor](_.storeRequestConfiguration(dto))
        .mapError(err => ErrorInfo(err.getMessage))
    }

  def evaluateMockServerEndpoint: ZServerEndpoint[AppEnv, Any] =
    evaluateMockEndpoint.zServerLogic { dto =>
      ZIO
        .serviceWithZIO[services.RequestProcessor](_.invokeRequest(dto))
        .mapError(err => ErrorInfo(err.getMessage))
    }

  val endpoints: List[ZServerEndpoint[AppEnv, Any]] = List(
    initMockServerEndpoint,
    evaluateMockServerEndpoint
  )

  val routes: HttpRoutes[RIO[AppEnv, *]] =
    ZHttp4sServerInterpreter().from(endpoints).toRoutes
}
