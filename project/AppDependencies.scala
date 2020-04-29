import play.core.PlayVersion
import sbt._

object AppDependencies {

  val hmrcMongoVersion = "0.28.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-backend-play-27" % "2.4.0",
    "org.yaml"               %  "snakeyaml"                 % "1.25",
    "uk.gov.hmrc"            %% "github-client"             % "2.11.0",
    "org.scala-lang.modules" %% "scala-parser-combinators"  % "1.1.2",
    "org.typelevel"          %% "cats-core"                 % "2.1.1",
    "org.typelevel"          %% "alleycats-core"            % "2.1.1",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sns"   % "1.1.2",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sqs"   % "1.1.2",
    // akka-stream-alpakka-sns depends on 10.1.10 which isn't compatible with play's akka version 10.1.11
    "com.typesafe.akka"      %% "akka-http"                 % "10.1.11",
    "io.swagger"             %% "swagger-play2"             % "1.6.1",
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-27"        % hmrcMongoVersion
  )

  val test: Seq[ModuleID] = Seq(
    "org.scalatest"     %% "scalatest"               % "3.1.0"             % Test,
    "com.typesafe.play" %% "play-test"               % PlayVersion.current % Test,
    "org.mockito"       %  "mockito-core"            % "3.2.4"             % Test,
    "org.scalatestplus" %% "scalatestplus-mockito"   % "1.0.0-M2"          % Test,
    "com.typesafe.akka" %% "akka-testkit"            % "2.5.26"            % Test,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-27" % hmrcMongoVersion    % Test
  )
}
