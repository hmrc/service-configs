import play.core.PlayVersion.current
import sbt._

object AppDependencies {

  val compile = Seq(

    "uk.gov.hmrc"             %% "bootstrap-play-26"        % "0.36.0",
    "org.yaml"                %  "snakeyaml"                % "1.17",
    "uk.gov.hmrc"             %% "github-client"            % "2.8.0",
    "uk.gov.hmrc"             %% "play-config"              % "7.0.0",
    "uk.gov.hmrc"             %% "mongo-lock"               % "6.10.0-play-26",
    "org.scala-lang.modules"  %% "scala-parser-combinators" % "1.1.1",
    "org.typelevel"           %% "cats-core"                % "1.1.0",
    "org.typelevel"           %% "alleycats-core"           % "1.1.0"
  )

  val test = Seq(
    "org.scalatest"           %% "scalatest"                % "3.0.5"                 % "test",
    "uk.gov.hmrc"             %% "hmrctest"                 % "3.3.0"                 % "test",
    "com.typesafe.play"       %% "play-test"                % current                 % "test",
    "org.pegdown"             %  "pegdown"                  % "1.6.0"                 % "test, it",
    "uk.gov.hmrc"             %% "service-integration-test" % "0.5.0-play-26"                 % "test, it",
    "uk.gov.hmrc"             %% "reactivemongo-test"       % "4.8.0-play-26"         % "test",
    "org.scalatestplus.play"  %% "scalatestplus-play"       % "3.0.0"                 % "test, it",
    "org.mockito"             %  "mockito-core"             % "2.3.5"                 % "test, it",
    // force dependencies due to security flaws found in xercesImpl 2.11.0
    "xerces"                  % "xercesImpl"                % "2.12.0"                % "test, it"
  )

}
