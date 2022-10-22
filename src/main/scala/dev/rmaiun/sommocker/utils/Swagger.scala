package dev.rmaiun.sommocker.utils

import io.circe.Json

object Swagger {

  val initMockJsonExample: Json = Json.obj(
    ("$schema", Json.fromString("../../../schemas/mari-disaggregation.solution.schema.json")),
    ("optimizationId", Json.fromString("7a6b20a3-e40d-4130-ad1a-d856b9adc81c")),
    ("status", Json.fromString("success")),
    ("cTs", Json.fromString("2021-10-27T17:01:09.779Z")),
    ("payload", Json.obj())
  )
}
