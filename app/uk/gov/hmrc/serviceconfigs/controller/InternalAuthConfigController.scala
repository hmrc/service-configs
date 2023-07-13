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

import io.swagger.annotations.{Api, ApiOperation, ApiParam}
import play.api.{Configuration, Logging}
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.ConfigJson
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.persistence.InternalAuthConfigRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class InternalAuthConfigController @Inject()(
  internalAuthConfigRepository: InternalAuthConfigRepository,
  mcc          : MessagesControllerComponents
)(implicit
  ec: ExecutionContext
) extends BackendController(mcc) with Logging {

  implicit val format = uk.gov.hmrc.serviceconfigs.model.InternalAuthConfig.format

  @ApiOperation(
    value = "Retrieves all of the config for a given service, broken down by environment",
    notes = """Searches all config sources for all environments and pulls out the the value of each config key"""
  )
  def serviceConfig(
    @ApiParam(value = "The service name to query") serviceName: ServiceName
  ): Action[AnyContent] = Action.async { implicit request =>
    logger.info(s"Called ${request.uri} with ${serviceName.asString} ")
    internalAuthConfigRepository.findByService(serviceName).map { e =>
      Ok(Json.toJson(e))
    }
  }

}
