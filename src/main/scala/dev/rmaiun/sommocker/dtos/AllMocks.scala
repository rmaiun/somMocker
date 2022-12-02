package dev.rmaiun.sommocker.dtos

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec

case class AllMocks(mocks: List[ConfigurationDataDto])

object AllMocks {
  implicit val AllMocksCodec: Codec[AllMocks] = deriveCodec[AllMocks]
}
