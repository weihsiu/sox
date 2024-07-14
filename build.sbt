name := "sox"
version := "0.0.1"
scalaVersion := "3.4.2"

Compile / run / fork := true
Compile / run / connectInput := true
Compile / run / javaOptions ++= Seq()

libraryDependencies ++= Seq(
  "org.scodec" %% "scodec-core" % "2.3.0",
  "com.softwaremill.ox" %% "core" % "0.3.0",
  "com.softwaremill.sttp.client4" %% "core" % "4.0.0-M16",
  "com.softwaremill.sttp.tapir" %% "tapir-netty-server-sync" % "1.10.13",
  "ch.qos.logback" % "logback-classic" % "1.5.6",
  "org.scalameta" %% "munit" % "1.0.0" % Test,
  "eu.monniot" %% "scala3mock" % "0.6.3" % Test
)
