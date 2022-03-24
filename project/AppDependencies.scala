import play.core.PlayVersion
import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val hmrcMongoVersion     = "0.62.0"
  val bootstrapPlayVersion = "5.21.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-backend-play-28" % bootstrapPlayVersion,
    "org.yaml"               %  "snakeyaml"                 % "1.27",
    "org.scala-lang.modules" %% "scala-parser-combinators"  % "1.1.2",
    "org.typelevel"          %% "cats-core"                 % "2.4.2",
    "org.typelevel"          %% "alleycats-core"            % "2.1.1",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sns"   % "2.0.2",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sqs"   % "2.0.2",
    "com.typesafe.akka"      %% "akka-http"                 % PlayVersion.akkaHttpVersion,
    // using the fork of io.swagger:swagger-play2 from 'com.iterable' to get around ://github.com/swagger-api/swagger-play/issues/152
    "com.iterable"           %% "swagger-play"              % "2.0.1",
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-28"        % hmrcMongoVersion,
    ws
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion    % Test,
    "org.mockito"            %  "mockito-core"            % "3.7.7"                 % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.16.23"               % Test,
    "com.typesafe.akka"      %% "akka-testkit"            % PlayVersion.akkaVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28" % hmrcMongoVersion        % Test
  )
}
