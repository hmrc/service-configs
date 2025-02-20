import play.sbt.PlayImport.PlayKeys.playDefaultPort
import play.sbt.routes.RoutesKeys

lazy val microservice = Project("service-configs", file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    scalaVersion        :=  "3.3.5",
    majorVersion        :=  1,
    playDefaultPort     :=  8460,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    scalacOptions       +=  "-Wconf:src=routes/.*:s",
    scalacOptions       +=  "-Wconf:msg=Flag.*repeatedly:s",
    resolvers           +=  Resolver.jcenterRepo,
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.serviceconfigs.model.{Environment, FilterType, DeploymentDateRange, ServiceName, ServiceType, Tag, TeamName, Version, ArtefactName, RouteType}",
      "uk.gov.hmrc.serviceconfigs.model.QueryBinders._",
    )
  )
