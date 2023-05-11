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

import cats.instances.all._
import cats.syntax.all._
import play.api.Logger

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, GithubRawConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.persistence.{SlugInfoRepository, SlugVersionRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository       : SlugInfoRepository
, slugVersionRepository    : SlugVersionRepository
, appConfigService         : AppConfigService
, releasesApiConnector     : ReleasesApiConnector
, teamsAndReposConnector   : TeamsAndRepositoriesConnector
, githubRawConnector       : GithubRawConnector
, configConnector          : ConfigConnector
)(implicit
  ec: ExecutionContext
) {

  private val logger = Logger(getClass)

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
      _                      <- allServiceDeployments.toList.foldLeftM(()) { case (_, (serviceName, deployments)) =>
                                  deployments.foldLeftM(()) {
                                    case (_, (env, None            )) => cleanUpDeployment(env, serviceName)
                                    case (_, (env, Some(deployment))) => updateDeployment(env, serviceName, deployment)
                                  }
                                }
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
        _           <- slugInfoRepository.clearFlag(SlugInfoFlag.ForEnvironment(env), serviceName)
        serviceType <- appConfigService.serviceType(env, serviceName)
        _           <- serviceType match {
                         case Some(st) => appConfigService.deleteAppConfigCommon(
                                            environment = env,
                                            serviceName = serviceName,
                                            serviceType = st
                                          )
                         case None     => Future.unit
                       }
        _           <- appConfigService.deleteAppConfigBase(
                         environment = env,
                         serviceName = serviceName
                       )
        _           <- appConfigService.deleteAppConfigEnv(
                         environment = env,
                         serviceName = serviceName
                       )
      } yield ()

    private def updateDeployment(
      env        : Environment,
      serviceName: String,
      deployment : ReleasesApiConnector.Deployment
    )(implicit
      hc: HeaderCarrier
    ): Future[Unit] =
      for {
        _ <- slugInfoRepository.setFlag(SlugInfoFlag.ForEnvironment(env), serviceName, deployment.version)
        _ <- deployment.config.toList.traverse { config =>
               config.repoName match {
                 case "app-config-common" =>
                   for {
                     optAppConfigCommon <- configConnector.appConfigCommonYaml(env, config.fileName, config.commitId)
                     _                  <- optAppConfigCommon match {
                                             case Some(appConfigCommon) => appConfigService.putAppConfigCommon(
                                                                             serviceName = serviceName,
                                                                             fileName    = config.fileName,
                                                                             commitId    = config.commitId,
                                                                             content     = appConfigCommon
                                                                           )
                                             case None                  => Future.successful(logger.warn(s"No app-config-common found for ${env.asString}, ${config.fileName} ${config.commitId}"))
                                           }
                   } yield ()
                 case "app-config-base" =>
                   for {
                     optAppConfigBase <- configConnector.appConfigBaseConf(serviceName, config.commitId)
                     _                <- optAppConfigBase match {
                                            case Some(appConfigBase) => appConfigService.putAppConfigBase(
                                                                          environment = env,
                                                                          serviceName = serviceName,
                                                                          commitId    = config.commitId,
                                                                          content     = appConfigBase
                                                                        )
                                            case None                => Future.successful(logger.warn(s"No app-config-base found for $serviceName, ${config.commitId}"))
                                          }
                   } yield ()
                 case s"app-config-${_}" =>
                   for {
                     optAppConfigEnv <- configConnector.appConfigEnvYaml(env, serviceName, config.commitId)
                     _               <- optAppConfigEnv match {
                                           case Some(appConfigEnv) => appConfigService.putAppConfigEnv(
                                                                       environment = env,
                                                                       serviceName = serviceName,
                                                                       commitId    = config.commitId,
                                                                       content     = appConfigEnv
                                                                     )
                                           case None               => Future.successful(logger.warn(s"No app-config-env found for $serviceName ${config.commitId}"))
                                         }
                   } yield ()
                 case other => Future.successful(logger.warn(s"Received commitId for unexpected repo $other"))
               }
             }
      } yield ()
}
