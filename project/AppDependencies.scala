import play.core.PlayVersion.current
import sbt._

object AppDependencies {

  val akkaVersion      = "2.5.23"
  val hmrcMongoVersion = "0.20.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-play-26"        % "0.42.0",
    "org.yaml"               % "snakeyaml"                 % "1.24",
    "uk.gov.hmrc"            %% "github-client"            % "2.10.0",
    "uk.gov.hmrc"            %% "play-config"              % "7.5.0",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "1.1.2",
    "org.typelevel"          %% "cats-core"                % "1.6.1",
    "org.typelevel"          %% "alleycats-core"           % "1.6.1",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sns"  % "1.1.0",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sqs"  % "1.1.0",
    "io.swagger"             %% "swagger-play2"            % "1.6.1",
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-26"       % hmrcMongoVersion
  )

  val test: Seq[ModuleID] = Seq(
    "org.scalatest"     %% "scalatest"              % "3.1.0"          % Test,
    "uk.gov.hmrc"       %% "hmrctest"               % "3.3.0"          % Test,
    "com.typesafe.play" %% "play-test"              % current          % Test,
    "org.mockito"       % "mockito-core"            % "3.1.0"          % Test,
    "org.scalatestplus" %% "scalatestplus-mockito"  % "1.0.0-M2"       % Test,
    // force dependencies due to security flaws found in xercesImpl 2.11.0
    "xerces"            % "xercesImpl"              % "2.12.0"         % Test,
    "com.typesafe.akka" %% "akka-testkit"           % akkaVersion      % Test,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test"        % hmrcMongoVersion % Test
  )

  // Ensure akka versions do not mismatch
  val overrides: Seq[ModuleID] = Seq(
    "com.typesafe.akka" %% "akka-actor"  % akkaVersion,
    "com.typesafe.akka" %% "akka-slf4j"  % akkaVersion,
    "com.typesafe.akka" %% "akka-stream" % akkaVersion
  )
}
