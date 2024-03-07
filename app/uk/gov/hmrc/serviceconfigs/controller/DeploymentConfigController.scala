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

import play.api.libs.json.{Json, Writes}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ServiceName, TeamName}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigController @Inject()(
  deploymentConfigRepository   : DeploymentConfigRepository,
  teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector,
  cc                           : ControllerComponents
)(implicit
  ec                           : ExecutionContext
) extends BackendController(cc) {

  implicit val dcw: Writes[DeploymentConfig] = DeploymentConfig.apiFormat

  def deploymentConfig(environments: Seq[Environment], serviceName: Option[ServiceName], teamName: Option[TeamName]): Action[AnyContent] =
    Action.async {
      for {
        reposForTeam       <- teamName match {
                               case Some(value) => teamsAndRepositoriesConnector.getRepos(teamName = Some(value), repoType = Some("Service")).map(Some.apply)
                               case None        => Future.successful(None)
                             }
        deploymentConfigs <- deploymentConfigRepository.find(environments, serviceName, reposForTeam)
      } yield
        (deploymentConfigs, serviceName) match {
          case (Nil, Some(serviceName)) => NotFound(s"Service: ${serviceName.asString} not found")
          case (Nil, _                ) => NotFound("No deployment configurations found")
          case _                        => Ok(Json.toJson(deploymentConfigs))
        }
    }
}
