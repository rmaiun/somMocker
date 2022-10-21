package dev.rmaiun.sommocker
import dev.rmaiun.sommocker.Boot.refLayer
import dev.rmaiun.sommocker.dtos.{ AlgorithmStructure, AlgorithmStructureSet, AmqpComponents, ConfigurationDataDto }
import dev.rmaiun.sommocker.services.RabbitInitializer.ZPublisher
import dev.rmaiun.sommocker.services.RequestProcessor
import io.circe.Json
import zio._
import zio.stream.ZStream
import zio.test._

object RequestProcessorSpec extends ZIOSpecDefault {
  val mockPublisher: ZPublisher = (_, _) => ZIO.succeed(())

  def spec: Spec[TestEnvironment with Scope, Throwable] = suite("RequestProcessorTest")(
    test("Successfully init mock") {
      val dto = ConfigurationDataDto("pc1", "or1", "ag1", 4, logsEnabled = false, Json.Null)
      for {
        rp <- ZIO.serviceWithZIO[RequestProcessor](_.storeRequestConfiguration(dto))
      } yield assertTrue(rp.processCode == dto.processCode)
    }
  ).provideLayer(
    ZLayer.make[RequestProcessor](
      Scope.default,
      refLayer,
      ZLayer.succeed(AlgorithmStructureSet(Set(AlgorithmStructure("ag1", AmqpComponents(mockPublisher, ZStream.empty, mockPublisher, mockPublisher))))),
      RequestProcessor.live
    )
  )
}
