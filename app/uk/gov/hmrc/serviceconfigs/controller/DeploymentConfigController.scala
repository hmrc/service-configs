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

package uk.gov.hmrc.serviceconfigs.controller

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository
import uk.gov.hmrc.serviceconfigs.service.DeploymentConfigService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigController @Inject()(
  deploymentConfigService           : DeploymentConfigService,
  deploymentConfigSnapshotRepository: DeploymentConfigSnapshotRepository,
  teamsAndRepositoriesConnector     : TeamsAndRepositoriesConnector,
  cc                                : ControllerComponents
)(implicit ec: ExecutionContext
) extends BackendController(cc) {

  implicit val dcw = DeploymentConfig.apiFormat
  implicit val dcsw = DeploymentConfigSnapshot.apiFormat

  def deploymentConfig(environments: Seq[Environment], serviceName: Option[ServiceName]): Action[AnyContent] =
    Action.async {
      deploymentConfigService.find(environments, serviceName)
        .map(deploymentConfigs => (deploymentConfigs, serviceName) match {
          case (Nil, Some(serviceName)) => NotFound(s"Service: ${serviceName.asString} not found")
          case (Nil, _) => NotFound("No deployment configurations found")
          case _        => Ok(Json.toJson(deploymentConfigs))
        })
    }

  def deploymentConfigGrouped(serviceName: Option[ServiceName], teamName: Option[TeamName], repoType: Option[String], sort: Option[String]): Action[AnyContent] =
    Action.async {

      val sn: Option[ServiceName] = serviceName.map(_.trimmed).filter(_.asString.nonEmpty)
      val tn : Option[TeamName]   = teamName.map(_.trimmed).filter(_.asString.nonEmpty)

      val getReposByTeam = tn match {
        case Some(value) => teamsAndRepositoriesConnector.getRepos(teamName = Some(value), repoType = repoType).map(Some(_))
        case None        => Future.successful(None)
      }

      for {
        teamRepos   <- getReposByTeam
        configs     <- deploymentConfigService.findGrouped(sn, teamRepos, sort)
      } yield  {
        Ok(Json.toJson(configs))
      }
    }


  def cleanupDuplicates(): Action[AnyContent] =
    Action {
      deploymentConfigSnapshotRepository.cleanupDuplicates()
      Accepted("")
    }
}
