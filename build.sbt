import play.sbt.PlayImport.PlayKeys.playDefaultPort
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val silencerVersion = "1.7.7"

lazy val microservice = Project("service-configs", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    scalaVersion := "2.12.15",
    majorVersion := 0,
    playDefaultPort := 8460,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test
  )
  .settings(publishingSettings: _*)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(
    // Use the silencer plugin to suppress warnings
    scalacOptions += "-P:silencer:pathFilters=routes",
    libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" % "silencer-plugin" % silencerVersion cross CrossVersion.full),
      "com.github.ghik" % "silencer-lib" % silencerVersion % Provided cross CrossVersion.full
    )
  )

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.10")
