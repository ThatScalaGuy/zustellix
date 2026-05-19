ThisBuild / scalaVersion := "3.3.6"
ThisBuild / organization := "de.thatscalaguy"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / licenses := Seq(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0")
)

val CatsEffectV = "3.6.1"
val Http4sV = "0.23.30"
val CirceV = "0.14.13"
val MulesV = "0.7.0"
val JwtScalaV = "10.0.4"
val Log4catsV = "2.7.1"
val LogbackV = "1.5.18"
val BouncyV = "1.79"
val MunitCEV = "2.1.0"
val ProxyVoleV = "1.1.9"
val OsciBibV = "2.4.8"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-Wunused:all",
  "-Xfatal-warnings"
)

lazy val root = (project in file("."))
  .aggregate(utils, dvdv, osciXmeld)
  .settings(
    name := "zustellix",
    publish / skip := true
  )

lazy val utils = (project in file("utils"))
  .settings(
    name := "utils",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectV,
      "org.bouncycastle" % "bcprov-jdk18on" % BouncyV,
      "org.bouncycastle" % "bcpkix-jdk18on" % BouncyV,
      "org.typelevel" %% "log4cats-slf4j" % Log4catsV,
      "ch.qos.logback" % "logback-classic" % LogbackV % Runtime,
      "org.typelevel" %% "munit-cats-effect" % MunitCEV % Test,
      "org.typelevel" %% "log4cats-noop" % Log4catsV % Test
    ),
    Test / fork := true
  )

lazy val dvdv = (project in file("dvdv"))
  .dependsOn(utils)
  .settings(
    name := "dvdv",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectV,
      "org.http4s" %% "http4s-core" % Http4sV,
      "org.http4s" %% "http4s-client" % Http4sV,
      "org.http4s" %% "http4s-ember-client" % Http4sV,
      "org.http4s" %% "http4s-circe" % Http4sV,
      "org.http4s" %% "http4s-dsl" % Http4sV,
      "io.circe" %% "circe-core" % CirceV,
      "io.circe" %% "circe-generic" % CirceV,
      "io.circe" %% "circe-parser" % CirceV,
      "io.chrisdavenport" %% "mules" % MulesV,
      "com.github.jwt-scala" %% "jwt-circe" % JwtScalaV,
      "org.typelevel" %% "log4cats-slf4j" % Log4catsV,
      "ch.qos.logback" % "logback-classic" % LogbackV % Runtime,
      "org.typelevel" %% "munit-cats-effect" % MunitCEV % Test,
      "org.http4s" %% "http4s-ember-server" % Http4sV % Test,
      "org.typelevel" %% "log4cats-noop" % Log4catsV % Test
    ),
    Test / fork := true
  )

lazy val osciXmeld = (project in file("osci-xmeld"))
  .dependsOn(dvdv, utils)
  .settings(
    name := "osci-xmeld",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectV,
      "de.osci" % "osci-bibliothek" % OsciBibV,
      "org.typelevel" %% "munit-cats-effect" % MunitCEV % Test
    ),
    scalacOptions += "-no-indent",
    Test / fork := true
  )
