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
import uk.gov.hmrc.serviceconfigs.persistence.{AppConfigBaseRepository, AppConfigCommonRepository, AppConfigEnvRepository, SlugInfoRepository, SlugVersionRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository       : SlugInfoRepository
, slugVersionRepository    : SlugVersionRepository
, appConfigBaseRepository  : AppConfigBaseRepository
, appConfigCommonRepository: AppConfigCommonRepository
, appConfigEnvRepository   : AppConfigEnvRepository
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
                                }
      _                      <- allServiceDeployments.toList.foldLeftM(()) { case (_, (serviceName, deployments)) =>
                                  deployments.foldLeftM(()) {
                                    case (_, (env, None            )) => slugInfoRepository.clearFlag(SlugInfoFlag.ForEnvironment(env), serviceName) // TODO should we clear up config here too?
                                    case (_, (env, Some(deployment))) => for {
                                                                            _ <- slugInfoRepository.setFlag(SlugInfoFlag.ForEnvironment(env), serviceName, deployment.version)
                                                                            _ <- deployment.config.toList.traverse {
                                                                                   case (s"app-config-${_}", commitId) =>
                                                                                     for {
                                                                                       optAppConfigEnv <- configConnector.serviceConfigYaml(env, serviceName, commitId)
                                                                                       _               <- optAppConfigEnv match {
                                                                                                            case Some(appConfigEnv) => appConfigEnvRepository.put(serviceName, env, s"$serviceName.yaml", commitId, appConfigEnv)
                                                                                                            case None               => Future.successful(logger.warn(s"No app-config-env found for $serviceName $commitId"))
                                                                                                          }
                                                                                     } yield ()
                                                                                   case (s"app-config-common", commitId) =>
                                                                                     for {
                                                                                       serviceType        <- Future.successful("") // TODO this requires looking in app-configEnv
                                                                                                                                   // can we get this added to deploymentEvent?
                                                                                       optAppConfigCommon <- configConnector.serviceCommonConfigYaml(env, serviceType, commitId)
                                                                                       _                  <- optAppConfigCommon match {
                                                                                                               case Some(appConfigCommon) => // TODO fileName requires serviceType mapping. Move this since the logic is replicated
                                                                                                                                             appConfigCommonRepository.put(serviceName, env, s"${env.asString}-$serviceType-common.yaml", commitId, appConfigCommon)
                                                                                                                                             // TODO we need to store this commitId somewhere so when looking up config for a particular service we can follow it back
                                                                                                               case None                  => Future.successful(logger.warn(s"No app-config-common found for ${env.asString}, $serviceType $commitId"))
                                                                                                             }
                                                                                     } yield ()
                                                                                   case ("app-config-base", commitId) =>
                                                                                     for {
                                                                                      optAppConfigBase <- configConnector.serviceConfigBaseConf(serviceName, commitId)
                                                                                       _               <- optAppConfigBase match {
                                                                                                            case Some(appConfigBase) => appConfigBaseRepository.put(serviceName, s"$serviceName.conf", env, commitId, appConfigBase)
                                                                                                            case None                => Future.successful(logger.warn(s"No app-config-base found for $serviceName, $commitId"))
                                                                                                          }
                                                                                     } yield ()
                                                                                   case (other, _) => Future.successful(logger.warn(s"Received commitId for unexpected repo $other"))
                                                                                 }
                                                                          } yield ()
                                  }
                                }
      _                      <- slugInfoRepository.clearFlags(SlugInfoFlag.values, decommissionedServices)
      _                      <- if (inactiveServices.nonEmpty) {
                                  logger.info(s"Removing latest flag from the following inactive services: ${inactiveServices.mkString(", ")}")
                                  slugInfoRepository.clearFlags(List(SlugInfoFlag.Latest), inactiveServices.toList)
                                } else Future.unit
      missingLatestFlag      =  serviceNames.intersect(activeRepos).diff(decommissionedServices).diff(latestServices)
      _                      <- if (missingLatestFlag.nonEmpty) {
                                  logger.warn(s"The following services are missing Latest flag - setting latest flag based on latest version: ${missingLatestFlag.mkString(",")}")
                                  missingLatestFlag.foldLeftM(()) { (_, serviceName) =>
                                    for {
                                      optVersion <- slugVersionRepository.getMaxVersion(serviceName)
                                      _          <- optVersion match {
                                                      case Some(version) => slugInfoRepository.setFlag(SlugInfoFlag.Latest, serviceName, version)
                                                      case None          => logger.warn(s"No max version found for $serviceName"); Future.unit
                                                    }
                                    } yield ()
                                  }
                                } else Future.unit
    } yield ()
}
