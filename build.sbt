ThisBuild / version := "1.0"
ThisBuild / scalaVersion := "2.12.16"
ThisBuild / organization := "org.example"

val spinalVersion = "1.7.3"
val spinalCore = "com.github.spinalhdl" %% "spinalhdl-core" % spinalVersion
val spinalLib = "com.github.spinalhdl" %% "spinalhdl-lib" % spinalVersion
val spinalIdslPlugin = compilerPlugin("com.github.spinalhdl" %% "spinalhdl-idsl-plugin" % spinalVersion)
val scalactic = "org.scalactic" %% "scalactic" % "3.2.14"
val scalatest = "org.scalatest" %% "scalatest" % "3.2.14" % "test"
val breeze = "org.scalanlp" %% "breeze" % "1.1"
val breezeViz = "org.scalanlp" %% "breeze-viz" % "1.1"

lazy val root = (project in file("."))
  .settings(
    name := "CacheSNN",
    libraryDependencies ++= Seq(
      spinalCore, spinalLib, spinalIdslPlugin,
      scalactic, scalatest,
      breeze, breezeViz
    )
  )

fork := true
