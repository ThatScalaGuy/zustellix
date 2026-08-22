// JVM-only build: exclude the Scala.js/Native cross plugins, whose pinned
// sbt-scalajs 0.6.x / sbt-scala-native 0.3.x deps predate Maven Central
// publishing, but keep the crossproject core that TypelevelBspPlugin needs.
addSbtPlugin(
  ("org.typelevel" % "sbt-typelevel" % "0.8.6").excludeAll(
    ExclusionRule("org.portable-scala"),
    ExclusionRule("org.scala-native")
  )
)
addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "1.3.2")
