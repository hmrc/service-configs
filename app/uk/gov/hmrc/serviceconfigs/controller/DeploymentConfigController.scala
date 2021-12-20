/*
 * Copyright 2021 HM Revenue & Customs
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

import io.swagger.annotations.Api
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository
import uk.gov.hmrc.serviceconfigs.service.DeploymentConfigService

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

@Api("Deployment Config")
class DeploymentConfigController @Inject()(
  deploymentConfigService: DeploymentConfigService,
  deploymentConfigSnapshotRepository: DeploymentConfigSnapshotRepository,
  cc: ControllerComponents
  )(implicit ec: ExecutionContext) extends BackendController(cc) {

  implicit val dcw = DeploymentConfig.apiFormat
  implicit val dcsw = DeploymentConfigSnapshot.apiFormat

  def deploymentConfigForEnv(environment: String): Action[AnyContent] = Action.async {
    Environment
      .parse(environment)
      .fold(
        Future.successful(BadRequest(s"invalid environment name, must be one of ${Environment.values.mkString(",")}")))(
        env => deploymentConfigService.findAll(env).map(res => Ok(Json.toJson(res))))
  }

  def deploymentConfigForService(environment: String, service: String): Action[AnyContent] = Action.async {
    Environment
      .parse(environment)
      .fold(
        Future.successful(BadRequest(s"invalid environment"))
      )(
        env => deploymentConfigService
          .find(env, service)
          .map(_.fold(NotFound(s"Service: $service not found"))(cfg => Ok(Json.toJson(cfg))))
      )
  }

  def deploymentConfigHistoryForService(service: String): Action[AnyContent] =
    Action.async {
      deploymentConfigSnapshotRepository
        .snapshotsForService(service)
        .map(dch => Ok(Json.toJson(dch)))
    }

}
