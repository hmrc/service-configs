/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.Logging
import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.model.{ArtefactName, RepoName, ServiceName, ServiceToRepoName}
import uk.gov.hmrc.serviceconfigs.persistence.{DeploymentConfigRepository, ServiceToRepoNameRepository}
import uk.gov.hmrc.serviceconfigs.util.ZipUtil

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceToRepoNameService @Inject()(
  configAsCodeConnector       : ConfigAsCodeConnector
, deploymentConfigRepository  : DeploymentConfigRepository
, serviceToRepoNamesRepository: ServiceToRepoNameRepository
)(implicit ec: ExecutionContext
) extends Logging {

  def update(): Future[Unit] =
    for {
      configs      <- deploymentConfigRepository.find()
      fromConfig    = configs
                         .collect {
                           case config if config.artefactName.exists(_ != config.serviceName.asString) =>
                             (config.serviceName, config.artefactName)
                         }
                         .collect {
                           case (serviceName, Some(artefactName)) =>
                             ServiceToRepoName(serviceName, ArtefactName(artefactName), RepoName(artefactName))
                         }
                         .distinct
            _      <- Future.successful(logger.info("Fetching slug and repo name discrepancies from Build Jobs"))
      zip          <- configAsCodeConnector.streamBuildJobs()
      regex         = """jobs/live/(.*).groovy""".r
      fromBuildJobs = ZipUtil
                        .findServiceToRepoNames(zip, regex)
                        .map { case (repo, slug) =>
                          ServiceToRepoName(
                            serviceName  = ServiceName(slug),
                            artefactName = ArtefactName(slug),
                            repoName     = RepoName(repo)
                          )
                        }
      _             = serviceToRepoNamesRepository.putAll(fromConfig ++ fromBuildJobs)
    } yield ()
}
