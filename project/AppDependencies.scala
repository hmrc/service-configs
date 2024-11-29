import play.core.PlayVersion
import sbt._

object AppDependencies {

  val hmrcMongoVersion     = "2.3.0"
  val bootstrapPlayVersion = "9.5.0"
  val jacksonVersion       = "2.14.3"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"                      %% "bootstrap-backend-play-30" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"                %% "hmrc-mongo-play-30"        % hmrcMongoVersion,
    "org.yaml"                         %  "snakeyaml"                 % "2.3",
    "org.typelevel"                    %% "cats-core"                 % "2.12.0",
    "software.amazon.awssdk"           %  "sqs"                       % "2.29.15",
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-yaml"   % jacksonVersion
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion     % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"         % "3.2.18.0"               % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion         % Test
  )
}
