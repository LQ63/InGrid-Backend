ThisBuild / scalaVersion := "3.9.0" // aktuelle Scala LTS-Linie
ThisBuild / organization := "de.htwg.ingrid"
ThisBuild / version      := "0.1.0-SNAPSHOT"

val http4sVersion   = "0.23.38"
val circeVersion    = "0.14.17"
val logbackVersion  = "1.6.6"

lazy val root = (project in file("."))
  .settings(
    name := "ingrid-backend",
    libraryDependencies ++= Seq(
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "org.http4s"    %% "http4s-dsl"          % http4sVersion,
      "org.http4s"    %% "http4s-circe"        % http4sVersion,
      "io.circe"      %% "circe-core"          % circeVersion,
      "io.circe"      %% "circe-generic"       % circeVersion,
      "io.circe"      %% "circe-parser"        % circeVersion,
      "ch.qos.logback" % "logback-classic"     % logbackVersion,
      "org.scalatest" %% "scalatest"           % "3.2.20"      % Test,
      "org.http4s"    %% "http4s-ember-client" % http4sVersion % Test
    ),
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all"
    ),
    run / fork := true
  )
