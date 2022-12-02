package dev.rmaiun.sommocker

import dev.rmaiun.sommocker.Server.AppEnv
import dev.rmaiun.sommocker.dtos.ConfigurationDataDto._
import dev.rmaiun.sommocker.dtos.ConfigurationKeyDto._
import dev.rmaiun.sommocker.dtos._
import dev.rmaiun.sommocker.utils.Swagger
import io.circe.Json
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import sttp.tapir.PublicEndpoint
import sttp.tapir.generic.auto._
import sttp.tapir.json.circe._
import sttp.tapir.server.http4s.ztapir.ZHttp4sServerInterpreter
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.ztapir._
import zio.{ RIO, ZIO }
object Endpoints {
//  implicit val x1 = stringJsonBody.schema(implicitly[Schema[ConfigurationDataDto]].as[String])
//  implicit val x2 = stringJsonBody.schema(implicitly[Schema[ConfigurationKeyDto]].as[String])
//  implicit val x3  = stringJsonBody.schema(implicitly[Schema[ConfigurationKeyDto]].as[String])
//  implicit val x4  = stringJsonBody.schema(implicitly[Schema[ConfigurationKeyDto]].as[String])

  val initMockEndpoint: PublicEndpoint[ConfigurationDataDto, ErrorInfo, ConfigurationKeyDto, Any] = {
    endpoint.post
      .description("Init mock for particular process code and optimization run id")
      .in("mock" / "init")
      .in(
        jsonBody[ConfigurationDataDto]
          .example(ConfigurationDataDto("TEST_ALG#1", "mari", 4, logsEnabled = false, Swagger.initMockJsonExample))
      )
      .out(
        jsonBody[ConfigurationKeyDto]
          .example(ConfigurationKeyDto("TEST_ALG#1", "mari"))
      )
      .errorOut(jsonBody[ErrorInfo])
  }

  val listMockEndpoint: PublicEndpoint[Unit, ErrorInfo, AllMocks, Any] = {
    endpoint.get
      .description("List configured mocks")
      .in("mock" / "list")
      .out(jsonBody[AllMocks].example(AllMocks(List(ConfigurationDataDto("TEST_ALGORITHM_1", "mari", 4, logsEnabled = true, Json.Null)))))
      .errorOut(jsonBody[ErrorInfo])
  }

  val evaluateMockEndpoint: PublicEndpoint[ConfigurationKeyDto, ErrorInfo, EmptyResult, Any] = {
    endpoint.post
      .description("Evaluate response generation for preconfigured mock")
      .in("mock" / "evaluate")
      .in(jsonBody[ConfigurationKeyDto].example(ConfigurationKeyDto("TEST_ALGORITHM_1", "mari")))
      .out(jsonBody[EmptyResult].example(EmptyResult()))
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

  def listMockServerEndpoint: ZServerEndpoint[AppEnv, Any] =
    listMockEndpoint.zServerLogic { _ =>
      ZIO
        .serviceWithZIO[services.RequestProcessor](_.listMocks)
        .mapError(err => ErrorInfo(err.getMessage))
    }

  val endpoints: List[ZServerEndpoint[AppEnv, Any]] = List(
    initMockServerEndpoint,
    evaluateMockServerEndpoint,
    listMockServerEndpoint
  )

  val routes: HttpRoutes[RIO[AppEnv, *]] = {
    // docs/index.html?url=/docs/docs.yml
    ZHttp4sServerInterpreter().from(endpoints).toRoutes
  }

  val swaggerRoutes: HttpRoutes[RIO[AppEnv, *]] = ZHttp4sServerInterpreter()
    .from(SwaggerInterpreter().fromServerEndpoints[RIO[AppEnv, *]](Endpoints.endpoints, "sommocker", "0.1.0"))
    .toRoutes
}
