package dev.rmaiun.sommocker.dtos

case class AlgorithmStructureSet[F[_]](structures: Set[AlgorithmStructure[F]])
