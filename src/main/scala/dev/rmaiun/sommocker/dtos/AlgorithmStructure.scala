package dev.rmaiun.sommocker.dtos

import dev.rmaiun.sommocker.services.RabbitHelper.AmqpStructures

case class AlgorithmStructure[F[_]](code: String, structs: AmqpStructures[F])
