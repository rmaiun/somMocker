package dev.rmaiun.sommocker.services

import dev.rmaiun.sommocker.dtos.{AppConfiguration, BrokerConfiguration}
import pureconfig.ConfigSource
import pureconfig.generic.auto._

object ConfigProvider {

  def provideConfig: AppConfiguration =
    ConfigSource.default.loadOrThrow[AppConfiguration]
}
