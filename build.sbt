name := "ZipIO"

version := "0.0.1-SNAPSHOT"

scalaVersion := "2.11.7"

scalacOptions in Compile ++= Seq("-encoding", "UTF-8", "-target:jvm-1.8", "-deprecation", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint")

javacOptions in Compile ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint:unchecked", "-Xlint:deprecation")

sources in (Compile, doc) ~= (_ filter (_.getName endsWith ".scala"))

scalacOptions in (Compile, doc) ++= Seq("-doc-root-content", baseDirectory.value + "/src/main/root-doc.txt")

fork in Test := true

val akkaVersion = "2.3.9"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion,
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,

  "org.scalatest" %% "scalatest" % "2.2.1" % "test"
)