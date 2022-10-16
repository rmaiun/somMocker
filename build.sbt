val Http4sVersion          = "0.23.14"
val CirceVersion           = "0.14.2"
val MunitVersion           = "0.7.29"
val LogbackVersion         = "1.2.11"
val MunitCatsEffectVersion = "1.0.7"

lazy val assemblySettings = Seq(
  assembly / test := {},
  assembly / mainClass := Some("dev.rmaiun.sommocker.Main"),
  assembly / assemblyJarName := "sommocker.jar",
  assembly / assemblyMergeStrategy := {
    case PathList("META-INF", xs @ _*) => MergeStrategy.discard
    case x                             => MergeStrategy.first
  }
)

lazy val root = (project in file("."))
  .settings(
    organization := "dev.rmaiun",
    name := "sommocker",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.13.8",
    libraryDependencies ++= Seq(
      "org.http4s"            %% "http4s-ember-server" % Http4sVersion,
      "org.http4s"            %% "http4s-ember-client" % Http4sVersion,
      "org.http4s"            %% "http4s-circe"        % Http4sVersion,
      "org.http4s"            %% "http4s-dsl"          % Http4sVersion,
      "io.circe"              %% "circe-generic"       % CirceVersion,
      "io.circe"              %% "circe-parser"        % CirceVersion,
      "org.scalameta"         %% "munit"               % MunitVersion           % Test,
      "org.typelevel"         %% "munit-cats-effect-3" % MunitCatsEffectVersion % Test,
      "ch.qos.logback"         % "logback-classic"     % LogbackVersion         % Runtime,
      "org.scalameta"         %% "svm-subs"            % "20.2.0",
      "dev.profunktor"        %% "fs2-rabbit"          % "4.1.1",
      "org.typelevel"         %% "log4cats-slf4j"      % "2.2.0",
      "com.github.pureconfig" %% "pureconfig"          % "0.14.0",
      "dev.zio"               %% "zio"                 % "2.0.2",
      "dev.zio"               %% "zio-streams"         % "2.0.2",
      "eu.timepit"            %% "refined"             % "0.10.1",
      "nl.vroste"             %% "zio-amqp"            % "0.4.0",
      "dev.zio"               %% "zio-test"            % "2.0.2"                % Test
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.13.2" cross CrossVersion.full),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1"),
    testFrameworks += new TestFramework("munit.Framework")
  )
  .settings(assemblySettings: _*)

lazy val formatAll = taskKey[Unit]("Run scala formatter for all projects")

formatAll := {
  (root / Compile / scalafmt).value
  (root / Test / scalafmt).value
}
