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

import play.api.libs.json.{Format, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.service.{ResourceUsage, ResourceUsageService}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class ResourceUsageController @Inject()(
  resourceUsageService: ResourceUsageService,
  cc: ControllerComponents
  )(implicit ec: ExecutionContext) extends BackendController(cc) {

  private implicit val resourceUsageFormat: Format[ResourceUsage] =
    ResourceUsage.apiFormat

  def historicResourceUsageForService(serviceName: String): Action[AnyContent] =
    Action.async {
      resourceUsageService
        .historicResourceUsageForService(serviceName)
        .map(r => Ok(Json.toJson(r)))
    }
}
