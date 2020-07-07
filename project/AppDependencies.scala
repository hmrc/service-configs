import play.core.PlayVersion
import play.sbt.PlayImport.ws
import sbt._

object AppDependencies {

  val hmrcMongoVersion = "0.28.0"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-backend-play-27" % "2.6.0",
    "org.yaml"               %  "snakeyaml"                 % "1.25",
    "uk.gov.hmrc"            %% "github-client"             % "2.11.0",
    "org.scala-lang.modules" %% "scala-parser-combinators"  % "1.1.2",
    "org.typelevel"          %% "cats-core"                 % "2.1.1",
    "org.typelevel"          %% "alleycats-core"            % "2.1.1",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sns"   % "1.1.2",
    "com.lightbend.akka"     %% "akka-stream-alpakka-sqs"   % "1.1.2",
    // akka-stream-alpakka-sns depends on 10.1.10 which isn't compatible with play's akka version 10.1.11
    "com.typesafe.akka"      %% "akka-http"                 % "10.1.11",
    // using io.swagger:swagger-play2 fork to get around ://github.com/swagger-api/swagger-play/issues/152
    "com.iterable"           %% "swagger-play"              % "2.0.1",
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-play-27"        % hmrcMongoVersion,
    ws
  )

  val test: Seq[ModuleID] = Seq(
    "org.scalatest"     %% "scalatest"               % "3.1.0"             % Test,
    "com.typesafe.play" %% "play-test"               % PlayVersion.current % Test,
    "org.mockito"       %  "mockito-core"            % "3.2.4"             % Test,
    "org.mockito"       %% "mockito-scala-scalatest" % "1.13.10"           % Test,
    "com.typesafe.akka" %% "akka-testkit"            % "2.5.26"            % Test,
    "uk.gov.hmrc.mongo" %% "hmrc-mongo-test-play-27" % hmrcMongoVersion    % Test
  )

  val dependencyOverrides = Seq(
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.9.8" // swagger requires an older version of jackson than alpakka...
  )
}
