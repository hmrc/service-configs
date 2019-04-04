import play.sbt.PlayImport.PlayKeys.playDefaultPort
import uk.gov.hmrc.SbtArtifactory
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin.publishingSettings

val appName = "service-configs"

lazy val microservice = Project(appName, file("."))
  .enablePlugins(play.sbt.PlayScala, SbtAutoBuildPlugin, SbtGitVersioning, SbtDistributablesPlugin, SbtArtifactory)
  .settings(
    majorVersion                     := 0,
    playDefaultPort                  := 8460,
    libraryDependencies              ++= AppDependencies.compile ++ AppDependencies.test
  )
  .settings(publishingSettings: _*)
  .settings(resolvers += Resolver.jcenterRepo)

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.9")
