package dev.rmaiun.sommocker.dtos

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class ConfigurationKeyDto(processId: String, optimizationId: String)

object ConfigurationKeyDto {
  implicit val ConfigurationCreatedDtoCodec: Codec[ConfigurationKeyDto] =
    deriveCodec[ConfigurationKeyDto]
}
