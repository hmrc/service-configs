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
import play.api.Configuration
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.ConfigJson
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, TeamName, FilterType}
import uk.gov.hmrc.serviceconfigs.service.ConfigService
import uk.gov.hmrc.serviceconfigs.persistence.AppliedConfigRepository

import scala.concurrent.ExecutionContext

@Singleton
@Api("Github Config")
class ConfigController @Inject()(
  configuration: Configuration,
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

  private def maxSearchLimit = configuration.get[Int]("config-search.max-limit")
  @ApiOperation(
    value = "Search for config using the list query params below. Valid filter type are: 'contains', 'doesNotContain', 'equalTo', 'notEqualTo' or 'isEmpty'",
    notes = "Queries are not allowed to be over the configured max search limit"
  )
  def search(
    @ApiParam(value = "The key to query"     ) key            : Option[String],
    @ApiParam(value = "The key filter type"  ) keyFilterType  : FilterType,
    @ApiParam(value = "The value to query."  ) value          : Option[String],
    @ApiParam(value = "The value filter type") valueFilterType: FilterType,
    @ApiParam(value = "Environment filter"   ) environment    : Seq[Environment],
    @ApiParam(value = "Team name filter"     ) teamName       : Option[TeamName],
  ): Action[AnyContent] = Action.async {
    implicit val acf = AppliedConfigRepository.AppliedConfig.format
    configService
      .search(key, keyFilterType, value, valueFilterType, environment, teamName)
      .map {
        case k if (k.size > maxSearchLimit) => Forbidden(s"Queries returning over $maxSearchLimit results are not allowed")
        case k                              => Ok(Json.toJson(k))
      }
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
