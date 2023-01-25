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
import uk.gov.hmrc.serviceconfigs.connector.{GithubRawConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.persistence.{SlugInfoRepository, SlugVersionRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoService @Inject()(
  slugInfoRepository     : SlugInfoRepository
, slugVersionRepository  : SlugVersionRepository
, releasesApiConnector   : ReleasesApiConnector
, teamsAndReposConnector : TeamsAndRepositoriesConnector
, githubRawConnector     : GithubRawConnector
)(implicit
  ec: ExecutionContext
) {

  private val logger = Logger(getClass)

  def updateMetadata()(implicit hc: HeaderCarrier): Future[Unit] =
    for {
      serviceNames           <- slugInfoRepository.getUniqueSlugNames
      serviceDeploymentInfos <- releasesApiConnector.getWhatIsRunningWhere
      activeRepos            <- teamsAndReposConnector.getRepos(archived = Some(false))
                                  .map(_.map(_.name))
      decommissionedServices <- githubRawConnector.decommissionedServices
      latestServices         <- slugInfoRepository.getAllLatestSlugInfos
                                  .map(_.map(_.name))
      inactiveServices       =  latestServices.diff(activeRepos)
      allServiceDeployments  =  serviceNames.map { serviceName =>
                                  val deployments       = serviceDeploymentInfos.find(_.serviceName == serviceName).map(_.deployments)
                                  val deploymentsByFlag = Environment.values
                                                            .map { env =>
                                                               ( SlugInfoFlag.ForEnvironment(env)
                                                               , deployments.flatMap(
                                                                     _.find(_.optEnvironment.contains(env))
                                                                      .map(_.version)
                                                                   )
                                                               )
                                                             }
                                  (serviceName, deploymentsByFlag)
                                }
      _                      <- allServiceDeployments.toList.foldLeftM(()) { case (_, (serviceName, deployments)) =>
                                  deployments.foldLeftM(()) {
                                    case (_, (flag, None         )) => slugInfoRepository.clearFlag(flag, serviceName)
                                    case (_, (flag, Some(version))) => slugInfoRepository.setFlag(flag, serviceName, version)
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
