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
import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.ConfigJson
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, TeamName}
import uk.gov.hmrc.serviceconfigs.service.ConfigService
import uk.gov.hmrc.serviceconfigs.persistence.AppliedConfigRepository

import scala.concurrent.ExecutionContext

@Singleton
@Api("Github Config")
class ConfigController @Inject()(
  configService: ConfigService,
  mcc          : MessagesControllerComponents
)(implicit
  ec: ExecutionContext
) extends BackendController(mcc)
     with ConfigJson {

  @ApiOperation(
    value = "Retrieves all of the config for a given service, broken down by environment",
    notes = """Searches all config sources for all environments and pulls out the the value of each config key"""
  )
  def serviceConfig(
    @ApiParam(value = "The service name to query") serviceName: ServiceName,
    @ApiParam(value = "Latest or As Deployed")     latest     : Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    configService.configByEnvironment(serviceName, latest).map { e =>
      Ok(Json.toJson(e))
    }
  }

  @ApiOperation(
    value = "Retrieves all of the config for a given service, broken down by config key",
    notes = """Searches all config sources for all environments and pulls out the the value of each config key"""
  )
  def configByKey(
    @ApiParam(value = "The service name to query") serviceName: ServiceName,
    @ApiParam(value = "Latest or As Deployed")     latest     : Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    configService.configByKey(serviceName, latest).map { k =>
      Ok(Json.toJson(k))
    }
  }

  @ApiOperation(
    value = "Retrieves all uses of the config key, across all Environments and ServiceNames, unless filtered."
  )
  def search(
    @ApiParam(value = "The key to query. Quotes required for an exact match") key        : String,
    @ApiParam(value = "Environment filter"                                  ) environment: Seq[Environment],
    @ApiParam(value = "Team name filter"                                    ) teamName   : Option[TeamName]
  ): Action[AnyContent] = Action.async {
    implicit val acf = AppliedConfigRepository.AppliedConfig.format
    configService
      .find(key, environment, teamName)
      .map(k => Ok(Json.toJson(k)))
  }
  @ApiOperation(
    value = "Retrieves all config keys, unless filtered."
  )
  def configKeys(
    @ApiParam(value = "Team name filter") teamName: Option[TeamName]
  ): Action[AnyContent] = Action.async {
    configService
      .findConfigKeys(teamName)
      .map(res => Ok(Json.toJson(res)))
  }
}
