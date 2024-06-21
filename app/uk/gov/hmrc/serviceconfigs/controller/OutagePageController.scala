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

import play.api.libs.json.{Format, Json}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.service.OutagePageService

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class OutagePageController @Inject()(
  outagePageService: OutagePageService,
  cc               : ControllerComponents
)(using
  ec: ExecutionContext
) extends BackendController(cc):

  def searchByServiceName(serviceName: ServiceName): Action[AnyContent] =
    given Format[Environment] = Environment.format
    Action.async:
      for
        optEnvironments <- outagePageService.findByServiceName(serviceName)
        result          =  optEnvironments.fold(NotFound(""))(e => Ok(Json.toJson(e)))
      yield result
