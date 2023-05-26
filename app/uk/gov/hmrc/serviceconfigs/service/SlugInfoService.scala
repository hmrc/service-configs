/*
 * Copyright 2023 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.serviceconfigs.service

import cats.data.EitherT
import cats.instances.all._
import cats.syntax.all._
import play.api.Logger

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, GithubRawConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.persistence.{AppliedConfigRepository, DeployedConfigRepository, SlugInfoRepository, SlugVersionRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository       : SlugInfoRepository
, slugVersionRepository    : SlugVersionRepository
, appliedConfigRepository  : AppliedConfigRepository
, appConfigService         : AppConfigService
, deployedConfigRepository : DeployedConfigRepository
, releasesApiConnector     : ReleasesApiConnector
, teamsAndReposConnector   : TeamsAndRepositoriesConnector
, githubRawConnector       : GithubRawConnector
, configConnector          : ConfigConnector
, configService            : ConfigService
)(implicit
  ec: ExecutionContext
) {

  private val logger = Logger(getClass)

  private case class Count(
    skipped: Int,
    removed: Int,
    updated: Int
  )

  // TODO should we listen to deployment events directly, rather than polling releases-api?
  def updateMetadata()(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      serviceNames           <- slugInfoRepository.getUniqueSlugNames()
      serviceDeploymentInfos <- releasesApiConnector.getWhatIsRunningWhere()
      activeRepos            <- teamsAndReposConnector.getRepos(archived = Some(false))
                                  .map(_.map(_.name))
      decommissionedServices <- githubRawConnector.decommissionedServices()
      latestServices         <- slugInfoRepository.getAllLatestSlugInfos()
                                  .map(_.map(_.name))
      inactiveServices       =  latestServices.diff(activeRepos)
      allServiceDeployments  =  serviceNames.map { serviceName =>
                                  val deployments      = serviceDeploymentInfos.find(_.serviceName == serviceName).map(_.deployments)
                                  val deploymentsByEnv = Environment.values
                                                           .map(env =>
                                                             ( env
                                                             , deployments.flatMap(_.find(_.optEnvironment.contains(env)))
                                                             )
                                                           )
                                  (serviceName, deploymentsByEnv)
                                } ++
                                  // map decomissioned services to No deployment in all environments in order to clean up
                                  decommissionedServices.map( _ -> Environment.values.map(_ -> None))
      _                      =  logger.info(s"Updating config")
      count                  <- allServiceDeployments.toList.foldLeftM(Count(0, 0, 0)) { case (acc, (serviceName, deployments)) =>
                                  deployments.foldLeftM(acc) {
                                    case (acc, (env, None            )) => cleanUpDeployment(env, serviceName)
                                                                             .map(_ => acc.copy(removed = acc.removed + 1))
                                    case (acc, (env, Some(deployment))) => updateDeployment(env, serviceName, deployment)
                                                                             .map(skipped => if (skipped)
                                                                                               acc.copy(skipped = acc.skipped + 1)
                                                                                             else acc.copy(updated = acc.updated + 1))
                                  }
                                }
      _                      =  logger.info(s"Config updated: skipped = ${count.skipped}, removed = ${count.removed}, updated = ${count.updated}")
      _                      <- // we don't need to clean up HEAD configs for decomissionedServices since this will be removed when the service file is removed from config repo
                                slugInfoRepository.clearFlags(List(SlugInfoFlag.Latest), decommissionedServices)
      _                      <- if (inactiveServices.nonEmpty) {
                                  logger.info(s"Removing latest flag from the following inactive services: ${inactiveServices.mkString(", ")}")
                                  slugInfoRepository.clearFlags(List(SlugInfoFlag.Latest), inactiveServices.toList)
                                } else Future.unit
      missingLatestFlag      =  serviceNames.intersect(activeRepos).diff(decommissionedServices).diff(latestServices)
      _                      <- if (missingLatestFlag.nonEmpty) {
                                  logger.warn(s"The following services are missing Latest flag - setting latest flag based on latest version: ${missingLatestFlag.mkString(",")}")
                                  missingLatestFlag.foldLeftM(())((_, serviceName) =>
                                    for {
                                      optVersion <- slugVersionRepository.getMaxVersion(serviceName)
                                      _          <- optVersion match {
                                                      case Some(version) => slugInfoRepository.setFlag(SlugInfoFlag.Latest, serviceName, version)
                                                      case None          => logger.warn(s"No max version found for $serviceName"); Future.unit
                                                    }
                                    } yield ()
                                  )
                                } else Future.unit
    } yield ()

    private def cleanUpDeployment(env: Environment, serviceName: String): Future[Unit] =
      for {
        _ <- slugInfoRepository.clearFlag(SlugInfoFlag.ForEnvironment(env), serviceName)
        _ <- deployedConfigRepository.delete(serviceName, env)
        _ <- appliedConfigRepository.delete(env, serviceName)
      } yield ()

    private def updateDeployment(
      env        : Environment,
      serviceName: String,
      deployment : ReleasesApiConnector.Deployment
    )(implicit
      hc: HeaderCarrier
    ): Future[Boolean] =
      for {
        _         <- slugInfoRepository.setFlag(SlugInfoFlag.ForEnvironment(env), serviceName, deployment.version)
        processed <- deployment.deploymentId match {
                       case Some(deploymentId) => deployedConfigRepository.hasProcessed(deploymentId)
                       case None               => Future.successful(false)
                     }
        _         <- if (!processed)
                       updateDeployedConfig(env, serviceName, deployment, deployment.deploymentId.getOrElse("undefined"))
                         .fold(e => logger.warn(s"Failed to update deployed config for $serviceName in $env: $e"), _ => ())
                     else
                       Future.unit
      } yield processed

    private def updateDeployedConfig(
      env         : Environment,
      serviceName : String,
      deployment  : ReleasesApiConnector.Deployment,
      deploymentId: String
    )(implicit
      hc: HeaderCarrier
    ): EitherT[Future, String, Unit] =
      for {
        deployedConfigMap <- deployment.config.toList.foldMapM[EitherT[Future, String, *], List[(String, String)]] { config =>
                                config.repoName match {
                                  case "app-config-common" =>
                                    for {
                                      optAppConfigCommon <- EitherT.right(configConnector.appConfigCommonYaml(env, config.fileName, config.commitId))
                                      appConfigCommon    <- optAppConfigCommon match {
                                                              case Some(appConfigCommon) => EitherT.pure[Future, String](appConfigCommon)
                                                              case None                  => EitherT.leftT[Future, String](s"Could not find app-config-common data for commit ${config.commitId}")
                                                            }
                                    } yield List("app-config-common" -> appConfigCommon)
                                  case "app-config-base" =>
                                    for {
                                      optAppConfigBase   <- EitherT.right(configConnector.appConfigBaseConf(serviceName, config.commitId))
                                      appConfigBase      <- optAppConfigBase match {
                                                              case Some(appConfigBase) => EitherT.pure[Future, String](appConfigBase)
                                                              case None                => EitherT.leftT[Future, String](s"Could not find app-config-base data for commit ${config.commitId}")
                                                            }
                                    } yield List("app-config-base" -> appConfigBase)
                                  case s"app-config-${_}" =>
                                    for {
                                      optAppConfigEnv    <- EitherT.right(configConnector.appConfigEnvYaml(env, serviceName, config.commitId))
                                      appConfigEnv       <- optAppConfigEnv match {
                                                              case Some(appConfigEnv) => EitherT.pure[Future, String](appConfigEnv)
                                                              case None               => EitherT.leftT[Future, String](s"Could not find app-config-${env.asString} data for commit ${config.commitId}")
                                                            }
                                    } yield List(s"app-config-${env.asString}" -> appConfigEnv)
                                  case other => EitherT.pure[Future, String] { logger.warn(s"Received commitId for unexpected repo $other"); List.empty }
                                }
                              }.map(_.toMap)
        deployedConfig    =  DeployedConfigRepository.DeployedConfig(
                                serviceName     = serviceName,
                                environment     = env,
                                deploymentId    = deploymentId,
                                appConfigBase   = deployedConfigMap.get("app-config-base"),
                                appConfigCommon = deployedConfigMap.get("app-config-common"),
                                appConfigEnv    = deployedConfigMap.get(s"app-config-${env.asString}")
                              )
        _                 <- EitherT.right(deployedConfigRepository.put(deployedConfig))
        // now we have stored the deployment configs, we can calculate the resulting configs
        deploymentConfig  <- EitherT.right(configService.resultingConfig(ConfigService.ConfigEnvironment.ForEnvironment(env), serviceName, latest = false))
        _                 <- if (deploymentConfig.nonEmpty)
                                EitherT.right[String](appliedConfigRepository.put(env, serviceName, deploymentConfig))
                             else
                               EitherT.pure[Future, String](logger.warn(s"No deployment config resolved for ${env.asString}, $serviceName"))
      } yield ()
}
