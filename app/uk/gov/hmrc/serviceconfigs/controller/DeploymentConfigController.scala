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
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository
import uk.gov.hmrc.serviceconfigs.service.DeploymentConfigService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class DeploymentConfigController @Inject()(
  deploymentConfigService           : DeploymentConfigService,
  deploymentConfigSnapshotRepository: DeploymentConfigSnapshotRepository,
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

  def cleanupDuplicates(): Action[AnyContent] =
    Action {
      deploymentConfigSnapshotRepository.cleanupDuplicates()
      Accepted("")
    }
}
