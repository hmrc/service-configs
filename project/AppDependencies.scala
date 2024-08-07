import play.core.PlayVersion
import play.sbt.PlayImport.{ws, ehcache}
import sbt._

object AppDependencies {

  val hmrcMongoVersion     = "2.2.0"
  val bootstrapPlayVersion = "9.2.0"
  val jacksonVersion       = "2.12.7"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"                      %% "bootstrap-backend-play-30" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"                %% "hmrc-mongo-play-30"        % hmrcMongoVersion,
    "org.yaml"                         %  "snakeyaml"                 % "1.28",
    "org.typelevel"                    %% "cats-core"                 % "2.12.0",
    "software.amazon.awssdk"           %  "sqs"                       % "2.26.31",
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-yaml"   % jacksonVersion,
    ws,
    ehcache
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion     % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"         % "3.2.18.0"               % Test,
    "org.scalatestplus"      %% "mockito-4-11"            % "3.2.17.0"               % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion         % Test
  )
}
