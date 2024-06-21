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

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

@Singleton
class ServiceToRepoNameService @Inject()(
  configAsCodeConnector       : ConfigAsCodeConnector
, deploymentConfigRepository  : DeploymentConfigRepository
, serviceToRepoNamesRepository: ServiceToRepoNameRepository
)(using
  ec: ExecutionContext
) extends Logging:
  import ServiceToRepoNameService._

  def update(): Future[Unit] =
    for
      configs       <- deploymentConfigRepository.find(applied = false)
      fromConfig    =  configs
                         .collect:
                           case config if config.artefactName.exists(_.asString != config.serviceName.asString) =>
                             (config.serviceName, config.artefactName)
                         .collect:
                           case (serviceName, Some(artefactName)) =>
                             ServiceToRepoName(serviceName, artefactName, RepoName(artefactName.asString))
                         .distinct
      _             =  logger.info("Fetching slug and repo name discrepancies from Build Jobs")
      zip           <- configAsCodeConnector.streamBuildJobs()
      fromBuildJobs =  extractServiceToRepoNames(zip)
                         .map:
                           case (repo, slug) =>
                             ServiceToRepoName(
                               serviceName  = ServiceName(slug),
                               artefactName = ArtefactName(slug),
                               repoName     = RepoName(repo)
                             )
      _             =  serviceToRepoNamesRepository.putAll(fromConfig ++ fromBuildJobs)
    yield ()

object ServiceToRepoNameService:
  val MicroserviceMatch: Regex =
    """.*new MvnMicroserviceJobBuilder\([^,]*,\s*['"]([^'"]+)['"].*""".r

  val AemMatch: Regex =
    """.*new MvnAemJobBuilder\([^,]*,\s*['"]([^'"]+)['"].*""".r

  val UploadSlugMatch: Regex =
    """.*\.andUploadSlug\([^,]*,[^,]*,\s*['"]([^'"]+)['"].*""".r

  private val pathRegex = """jobs/live/(.*).groovy""".r

  def extractServiceToRepoNames(zip: ZipInputStream): Seq[(String, String)] =
    ZipUtil
      .extractFromFiles(zip):
        case (pathRegex(_), lines) =>
          lines
            .foldLeft((Option.empty[String], Seq.empty[(String, String)])):
              case ((_             , acc), MicroserviceMatch(repoName)  ) => (Some(repoName), acc)
              case ((_             , acc), AemMatch(repoName)           ) => (Some(repoName), acc)
              case ((Some(repoName), acc), UploadSlugMatch(artefactName)) => (Some(repoName), acc :+ (repoName, artefactName) )
              case ((optRepoName   , acc), _                            ) => (optRepoName   , acc)
            ._2.iterator
        case _ => Iterator.empty
      .filterNot:
        case (repo, slug) => repo == slug
      .toSeq
