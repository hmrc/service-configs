import play.core.PlayVersion
import sbt._

object AppDependencies {

  val bootstrapPlayVersion = "9.18.2-RC2"
  val hmrcMongoVersion     = "2.7.0"
  val jacksonVersion       = "2.15.3"

  val compile: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"                      %% "bootstrap-backend-play-30" % bootstrapPlayVersion,
    "uk.gov.hmrc.mongo"                %% "hmrc-mongo-play-30"        % hmrcMongoVersion,
    "org.yaml"                         %  "snakeyaml"                 % "2.4",
    "org.typelevel"                    %% "cats-core"                 % "2.13.0",
    "software.amazon.awssdk"           %  "sqs"                       % "2.30.33",
    "com.fasterxml.jackson.dataformat" %  "jackson-dataformat-yaml"   % jacksonVersion,
    "org.playframework"                %% "play-routes-compiler"      % PlayVersion.current
  )

  val test: Seq[ModuleID] = Seq(
    "uk.gov.hmrc"            %% "bootstrap-test-play-30"  % bootstrapPlayVersion     % Test,
    "org.scalatestplus"      %% "scalacheck-1-17"         % "3.2.18.0"               % Test,
    "uk.gov.hmrc.mongo"      %% "hmrc-mongo-test-play-30" % hmrcMongoVersion         % Test
  )

  val it: Seq[ModuleID] = Seq.empty
}
