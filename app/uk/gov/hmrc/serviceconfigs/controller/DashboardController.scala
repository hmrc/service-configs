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
import uk.gov.hmrc.serviceconfigs.persistence.{KibanaDashboardRepository, GrafanaDashboardRepository}
import uk.gov.hmrc.serviceconfigs.service.DashboardService

import scala.concurrent.ExecutionContext

@Singleton
@Api("Dashboard")
class DashboardController @Inject()(
  kibanaDashboardRepository: KibanaDashboardRepository,
  grafanaDashboardRepository: GrafanaDashboardRepository,
  mcc: MessagesControllerComponents
)(implicit
  ec: ExecutionContext
) extends BackendController(mcc) {

  implicit val dashboardFormat = DashboardService.format

  @ApiOperation(
    value = "Retrieves Grafana Dashboard config for the given service",
    notes = "Grafana Dashboard config is extracted fromt the grafana-dashboards repo"
  )
  def grafana(
    @ApiParam(value = "The service name to query") serviceName: String
  ): Action[AnyContent] =
    Action.async {
      grafanaDashboardRepository
        .findByService(serviceName)
        .map(_.fold(NotFound: Result)(x => Ok(Json.toJson(x))))
    }

  @ApiOperation(
    value = "Retrieves Kibana Dashboard config for the given service",
    notes = "Kibana Dashboard config is extracted fromt the kibana-dashboards repo"
  )
  def kibana(
    @ApiParam(value = "The service name to query") serviceName: String
  ): Action[AnyContent] =
    Action.async {
      kibanaDashboardRepository
        .findByService(serviceName)
        .map(_.fold(NotFound: Result)(x => Ok(Json.toJson(x))))
    }
}
