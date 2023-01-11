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

import io.swagger.annotations.{Api, ApiOperation}
import javax.inject.{Inject, Singleton}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.service.BobbyService

import scala.concurrent.ExecutionContext

@Singleton
@Api("Bobby Rules")
class BobbyController @Inject()(bobbyService: BobbyService, mcc: MessagesControllerComponents)(
  implicit ec: ExecutionContext)
    extends BackendController(mcc) {

  @ApiOperation(
    value = "Retrieve the current set of bobby rules",
    notes = """Parses the list of bobby rules from the github bobby-config repo"""
  )
  def allRules(): Action[AnyContent] =
    Action.async {
      bobbyService.findAllRules().map { e =>
        Ok(e).as("application/json")
      }
    }
}
