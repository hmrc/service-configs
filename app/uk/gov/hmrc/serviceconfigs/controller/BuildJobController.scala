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

import io.swagger.annotations.{Api, ApiOperation, ApiParam}

import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Result}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.persistence.BuildJobRepository

import scala.concurrent.ExecutionContext

@Singleton
@Api("Build Job")
class BuildJobController @Inject()(
  buildJobRepository: BuildJobRepository,
  mcc: MessagesControllerComponents
)(implicit
  ec: ExecutionContext
) extends BackendController(mcc) {

  implicit val buildJobFormat = BuildJobRepository.format

  @ApiOperation(
    value = "Retrieves Build Jobs config for the given service",
    notes = "Build Job config is extracted fromt the build-jobs repo"
  )
  def buildJob(
    @ApiParam(value = "The service name to query") serviceName: String
  ): Action[AnyContent] =
    Action.async {
      buildJobRepository
        .findByService(serviceName)
        .map(_.fold(NotFound: Result)(x => Ok(Json.toJson(x))))
    }
}
