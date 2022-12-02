package dev.rmaiun.sommocker.dtos

import io.circe.generic.semiauto.deriveCodec
import io.circe.{ Codec, Json }
case class ConfigurationDataDto(
  algorithm: String,
  command: String,
  nodesQty: Int,
  logsEnabled: Boolean,
  resultMock: Json
)

object ConfigurationDataDto {
  implicit val CreateRequestConfigurationDtoCodec: Codec[ConfigurationDataDto] =
    deriveCodec[ConfigurationDataDto]

}
