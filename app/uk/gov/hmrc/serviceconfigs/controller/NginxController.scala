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

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Json, Format}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{Environment, FrontendRoutes, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.FrontendRouteRepository

import scala.concurrent.ExecutionContext

@Singleton
class NginxController @Inject()(
  db: FrontendRouteRepository,
  mcc: MessagesControllerComponents
)(using
  ec: ExecutionContext
) extends BackendController(mcc):

  private given Format[FrontendRoutes] = FrontendRoutes.format

  def searchByServiceName(serviceName: ServiceName): Action[AnyContent] =
    Action.async:
      db.findByService(serviceName)
        .map(FrontendRoutes.fromMongo)
        .map(Json.toJson(_))
        .map(Ok(_))

  def searchByEnvironment(environment: Environment): Action[AnyContent] =
    Action.async:
      db.findByEnvironment(environment)
        .map(FrontendRoutes.fromMongo)
        .map(Json.toJson(_))
        .map(Ok(_))

  def searchByFrontendPath(frontendPath: String): Action[AnyContent] =
    Action.async:
      db.searchByFrontendPath(frontendPath)
        .map(FrontendRoutes.fromMongo)
        .map(Json.toJson(_))
        .map(Ok(_))

  def allFrontendServices(): Action[AnyContent] =
    Action.async:
      db.findAllFrontendServices()
        .map(_.map(_.asString))
        .map(Json.toJson(_))
        .map(Ok(_))
