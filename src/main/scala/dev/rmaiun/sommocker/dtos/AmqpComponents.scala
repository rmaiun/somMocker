package dev.rmaiun.sommocker.dtos

import dev.rmaiun.sommocker.services.RabbitInitializer.{ ZConsumer, ZPublisher }

case class AmqpComponents(requestPublisher: ZPublisher, requestConsumer: ZConsumer, resultsPublisher: ZPublisher, logsPublisher: ZPublisher)
