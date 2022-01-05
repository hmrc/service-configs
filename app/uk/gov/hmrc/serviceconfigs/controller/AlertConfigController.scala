/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.serviceconfigs.model.AlertEnvironmentHandler
import uk.gov.hmrc.serviceconfigs.service.AlertConfigService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class AlertConfigController @Inject()(alertConfigService: AlertConfigService,
                                      cc: ControllerComponents)
                                     (implicit ec: ExecutionContext)
  extends BackendController(cc){

  def getAlertConfigs(): Action[AnyContent] = {
    implicit val writes: Writes[AlertEnvironmentHandler] = AlertEnvironmentHandler.mongoFormats
    Action.async {
      for {
        configs <- alertConfigService.findConfigs
        result = Ok(Json.toJson(configs))
      } yield result
    }
  }

  def getAlertConfigForService(serviceName: String): Action[AnyContent] = {
    Action.async {
      for {
        config <- alertConfigService.findOneConfig(serviceName)
        result = config.map(c => Ok(Json.toJson(c)(AlertEnvironmentHandler.mongoFormats))).getOrElse(NotFound)
      } yield result
    }
  }

}
