import play.core.PlayVersion
import play.sbt.PlayImport.{ws, ehcache}
import sbt._

object AppDependencies {

  val hmrcMongoVersion     = "1.8.0"
  val bootstrapPlayVersion = "8.5.0"
  val jacksonVersion       = "2.12.7"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"                      %% "bootstrap-backend-play-30" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"                %% "hmrc-mongo-play-30"        % hmrcMongoVersion,
    "org.yaml"                         %  "snakeyaml"                 % "1.28",
    "org.typelevel"                    %% "cats-core"                 % "2.8.0",
    "org.typelevel"                    %% "alleycats-core"            % "2.1.1",
    "software.amazon.awssdk"           %  "sqs"                       % "2.20.155",
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-yaml"   % jacksonVersion,
    ws,
    ehcache
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion     % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"         % "3.2.17.0"               % Test,
    "org.mockito"            %% "mockito-scala-scalatest" % "1.17.14"                % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion         % Test
  )
}
