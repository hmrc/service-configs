import play.sbt.PlayImport.PlayKeys.playDefaultPort
import play.sbt.routes.RoutesKeys

lazy val microservice = Project("service-configs", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    scalaVersion        :=  "2.13.10",
    majorVersion        :=  0,
    playDefaultPort     :=  8460,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    scalacOptions       +=  "-Wconf:src=routes/.*:s",
    resolvers           +=  Resolver.jcenterRepo,
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.serviceconfigs.model.{Environment, FilterType, ServiceName, TeamName}",
      "uk.gov.hmrc.serviceconfigs.model.QueryBinders._",
    )
  )

addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full)
