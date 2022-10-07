package dev.rmaiun.sommocker

import cats.effect.Concurrent
import cats.implicits._
import dev.rmaiun.sommocker.dtos.ConfigurationDataDto._
import dev.rmaiun.sommocker.dtos.ConfigurationKeyDto._
import dev.rmaiun.sommocker.dtos.{ ConfigurationDataDto, ConfigurationKeyDto }
import dev.rmaiun.sommocker.services.RequestProcessor
import io.circe.{ Decoder, Encoder }
import org.http4s.circe.{ jsonEncoderOf, jsonOf }
import org.http4s.dsl.Http4sDsl
import org.http4s.{ EntityDecoder, EntityEncoder, HttpRoutes }
object SommockerRoutes {

  implicit def entityEncoder[F[_], T: Encoder]: EntityEncoder[F, T]             = jsonEncoderOf[F, T]
  implicit def entityDecoder[F[_]: Concurrent, T: Decoder]: EntityDecoder[F, T] = jsonOf[F, T]
  def initMock[F[_]: Concurrent](rp: RequestProcessor[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._
    HttpRoutes.of[F] { case request @ POST -> Root / "initMock" =>
      for {
        dto    <- request.as[ConfigurationDataDto]
        result <- rp.storeRequestConfiguration(dto)
        resp   <- Ok(result)
      } yield resp
    }
  }

  def evaluateMock[F[_]: Concurrent](rp: RequestProcessor[F]): HttpRoutes[F] = {
    val dsl = new Http4sDsl[F] {}
    import dsl._
    HttpRoutes.of[F] { case request @ POST -> Root / "evaluateMock" =>
      for {
        dto    <- request.as[ConfigurationKeyDto]
        result <- rp.invokeRequest(dto)
        resp   <- Ok(result)
      } yield resp
    }
  }
}
