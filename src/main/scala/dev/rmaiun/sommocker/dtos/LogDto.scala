package dev.rmaiun.sommocker.dtos

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class LogDto(instanceId: String, timestamp: String, level: String, message: String)
object LogDto {
  implicit val LogDtoCodec: Codec[LogDto] =
    deriveCodec[LogDto]
}
