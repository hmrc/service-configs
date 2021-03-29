import play.core.PlayVersion
import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val hmrcMongoVersion     = "0.45.0"
  val hmrcBootstrapVersion = "4.0.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-backend-play-28" % hmrcBootstrapVersion,
    "org.yaml"               %  "snakeyaml"                 % "1.27",
    "uk.gov.hmrc"            %% "github-client"             % "3.0.0",
    "org.scala-lang.modules" %% "scala-parser-combinators"  % "1.1.2",
    "org.typelevel"          %% "cats-core"                 % "2.3.1",
    "org.typelevel"          %% "alleycats-core"            % "2.1.1",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sns"   % "2.0.2",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sqs"   % "2.0.2",
    // akka-stream-alpakka-sns depends on 10.1.11 which isn't compatible with play's akka version 10.1.13
    "com.typesafe.akka"      %% "akka-http"                 % "10.1.13",
    // using the fork of io.swagger:swagger-play2 from 'com.iterable' to get around ://github.com/swagger-api/swagger-play/issues/152
    "com.iterable"           %% "swagger-play"              % "2.0.1",
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-28"        % hmrcMongoVersion,
    ws
  )

  val test: Seq[ModuleID] = Seq(
    "org.scalatest"               %% "scalatest"               % "3.2.3"              % Test,
    "com.typesafe.play"           %% "play-test"               % PlayVersion.current  % Test,
    "org.scalatestplus.play"      %% "scalatestplus-play"      % "4.0.0"              % Test,
    "com.github.tomakehurst" %  "wiremock"                    % "1.58"                % Test,
  "org.mockito"                 %  "mockito-core"            % "3.7.7"                % Test,
    "org.mockito"                 %% "mockito-scala-scalatest" % "1.16.23"            % Test,
    "com.typesafe.akka"           %% "akka-testkit"            % "2.6.10"             % Test,
    "uk.gov.hmrc.mongo"           %% "hmrc-mongo-test-play-28" % hmrcMongoVersion     % Test
  )
}
