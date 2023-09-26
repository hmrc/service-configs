import play.core.PlayVersion
import play.sbt.PlayImport.{ws, ehcache}
import sbt._

object AppDependencies {

  val hmrcMongoVersion     = "1.3.0"
  val bootstrapPlayVersion = "7.21.0"
  val alpakkaVersion       = "4.0.0"
  val jacksonVersion       = "2.12.7"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"                      %% "bootstrap-backend-play-28" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"                %% "hmrc-mongo-play-28"        % hmrcMongoVersion,
    "org.yaml"                         %  "snakeyaml"                 % "1.28",
    "org.typelevel"                    %% "cats-core"                 % "2.8.0",
    "org.typelevel"                    %% "alleycats-core"            % "2.1.1",
    "com.lightbend.akka"               %% "akka-stream-alpakka-sns"   % alpakkaVersion,
    "com.lightbend.akka"               %% "akka-stream-alpakka-sqs"   % alpakkaVersion,
    // akka-stream-alpakka-sns depends on 10.1.11 which isn't compatible with play's akka version
    "com.typesafe.akka"                %% "akka-http"                 % PlayVersion.akkaHttpVersion,
    // using the fork of io.swagger:swagger-play2 from 'com.iterable' to get around ://github.com/swagger-api/swagger-play/issues/152
    "com.iterable"                     %% "swagger-play"              % "2.0.1",
    "com.fasterxml.jackson.module"     %% "jackson-module-scala"      % jacksonVersion, // update deps provided by swagger
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-yaml"   % jacksonVersion,
    ws,
    ehcache
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-28"  % bootstrapPlayVersion    % Test,
    "org.scalatestplus"      %% "scalacheck-1-14"         % "3.2.2.0"              % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.16.55"               % Test,
    "com.typesafe.akka"      %% "akka-testkit"            % PlayVersion.akkaVersion % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-28" % hmrcMongoVersion        % Test
  )
}
