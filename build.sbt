ThisBuild / tlBaseVersion := "0.6"
ThisBuild / scalaVersion := "3.3.9"
ThisBuild / organization := "de.thatscalaguy"
ThisBuild / organizationName := "ThatScalaGuy"
ThisBuild / startYear := Some(2026)
ThisBuild / licenses := Seq(License.Apache2)
ThisBuild / developers ++= List(
  tlGitHubDev("ThatScalaGuy", "Sven Herrmann")
)

val CatsEffectV = "3.7.1"
val Http4sV = "0.23.37"
val CirceV = "0.14.17"
val MulesV = "0.8.0"
val JwtScalaV = "12.0.0"
val Log4catsV = "2.8.0"
val BouncyV = "1.87"
val MunitCEV = "2.2.1"
val OsciBibV = "2.6.1"

ThisBuild / tlJdkRelease := Some(17)
ThisBuild / tlCiReleaseBranches := Seq()
// the tree is not formatted per .scalafmt.conf; CI checks headers only
ThisBuild / tlCiScalafmtCheck := false

ThisBuild / githubWorkflowJavaVersions := Seq(
  JavaSpec.temurin("25"),
  JavaSpec.temurin("21"),
  JavaSpec.temurin("17")
)
// clean.yml runs on every push and races the targets.tar handoff on tag builds
ThisBuild / githubWorkflowIncludeClean := false

lazy val root = (project in file("."))
  .enablePlugins(NoPublishPlugin)
  .aggregate(utils, dvdv, osci)
  .settings(
    name := "zustellix"
  )

lazy val utils = (project in file("utils"))
  .settings(
    name := "zustellix-utils",
    tlVersionIntroduced := Map("3" -> "0.1.1"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectV,
      "org.bouncycastle" % "bcprov-jdk18on" % BouncyV,
      "org.bouncycastle" % "bcpkix-jdk18on" % BouncyV,
      "org.typelevel" %% "log4cats-core" % Log4catsV,
      "org.typelevel" %% "munit-cats-effect" % MunitCEV % Test,
      "org.typelevel" %% "log4cats-noop" % Log4catsV % Test
    ),
    Test / fork := true
  )

lazy val dvdv = (project in file("dvdv"))
  .dependsOn(utils)
  .settings(
    name := "zustellix-dvdv",
    tlVersionIntroduced := Map("3" -> "0.1.1"),
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
      "org.typelevel" %% "log4cats-core" % Log4catsV,
      "org.typelevel" %% "munit-cats-effect" % MunitCEV % Test,
      "org.typelevel" %% "cats-effect-testkit" % CatsEffectV % Test,
      "org.http4s" %% "http4s-ember-server" % Http4sV % Test,
      "org.typelevel" %% "log4cats-noop" % Log4catsV % Test,
      "org.typelevel" %% "log4cats-testing" % Log4catsV % Test
    ),
    Test / fork := true
  )

lazy val osci = (project in file("osci"))
  .dependsOn(dvdv, utils)
  .settings(
    name := "zustellix-osci",
    tlVersionIntroduced := Map("3" -> "0.2.0"),
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % CatsEffectV,
      "de.osci" % "osci-bibliothek" % OsciBibV,
      "org.typelevel" %% "log4cats-core" % Log4catsV,
      "org.typelevel" %% "munit-cats-effect" % MunitCEV % Test,
      "org.typelevel" %% "log4cats-noop" % Log4catsV % Test,
      "org.typelevel" %% "log4cats-testing" % Log4catsV % Test
    ),
    scalacOptions += "-no-indent",
    Test / fork := true
  )
