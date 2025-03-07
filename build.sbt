import play.sbt.PlayImport.PlayKeys.playDefaultPort
import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.DefaultBuildSettings

ThisBuild / scalaVersion  := "3.3.5"
ThisBuild / majorVersion  := 1
ThisBuild / scalacOptions += "-Wconf:msg=Flag.*repeatedly:s"

lazy val microservice = Project("service-configs", file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    playDefaultPort     :=  8460,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    scalacOptions       +=  "-Wconf:src=routes/.*:s",
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.serviceconfigs.model.{Environment, FilterType, DeploymentDateRange, DigitalService, ServiceName, ServiceType, Tag, TeamName, Version, ArtefactName, RouteType}",
      "uk.gov.hmrc.serviceconfigs.model.QueryBinders._",
    )
  )

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test")
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies.it)
