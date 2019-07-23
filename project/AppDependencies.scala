import play.core.PlayVersion.current
import sbt._

object AppDependencies {

  val akkaVersion = "2.5.23"

  val compile = Seq(
    "uk.gov.hmrc" %% "bootstrap-play-26" % "0.42.0",
    "org.yaml" % "snakeyaml" % "1.24",
    "uk.gov.hmrc" %% "github-client" % "2.8.0",
    "uk.gov.hmrc" %% "play-config" % "7.0.0",
    "uk.gov.hmrc" %% "simple-reactivemongo" % "7.20.0-play-26",
    "uk.gov.hmrc" %% "mongo-lock" % "6.15.0-play-26",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
    "org.typelevel" %% "cats-core" % "1.6.1",
    "org.typelevel" %% "alleycats-core" % "1.6.1",
    "com.lightbend.akka" %% "akka-stream-alpakka-sns" % "1.1.0",
    "com.lightbend.akka" %% "akka-stream-alpakka-sqs" % "1.1.0",
    "io.swagger" %% "swagger-play2" % "1.6.1"
  )

  val test = Seq(
    "org.scalatest" %% "scalatest" % "3.0.8" % "test",
    "uk.gov.hmrc" %% "hmrctest" % "3.3.0" % "test",
    "com.typesafe.play" %% "play-test" % current % "test",
    "uk.gov.hmrc" %% "reactivemongo-test" % "4.15.0-play-26" % "test",
    "org.mockito" % "mockito-core" % "3.0.0" % "test",
    // force dependencies due to security flaws found in xercesImpl 2.11.0
    "xerces" % "xercesImpl" % "2.12.0" % "test",
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % Test
  )

  // Ensure akka versions do not mismatch
  val overrides = Set(
    "com.typesafe.akka" %% "akka-actor" % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion
  )
}
