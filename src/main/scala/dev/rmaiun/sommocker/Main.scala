package dev.rmaiun.sommocker

import cats.effect.{ExitCode, IO, IOApp}

object Main extends IOApp {
  def run(args: List[String]) =
    SommockerServer.stream[IO].compile.drain.as(ExitCode.Success)
}
