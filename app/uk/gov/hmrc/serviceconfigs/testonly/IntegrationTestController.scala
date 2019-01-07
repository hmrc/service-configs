/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.testonly

import javax.inject.Inject
import play.api.libs.json.{JsError, Reads}
import play.api.mvc.MessagesControllerComponents
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.serviceconfigs.NginxService
import uk.gov.hmrc.serviceconfigs.persistence.FrontendRouteRepo
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class IntegrationTestController @Inject()(routeRepo: FrontendRouteRepo, mcc: MessagesControllerComponents) extends BackendController(mcc) {

  import MongoFrontendRoute.formats

  def validateJson[A : Reads] = parse.json.validate(
    _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e))))

  def addRoutes() = Action.async(validateJson[Seq[MongoFrontendRoute]]) { implicit request =>
    Future.sequence(request.body.map(routeRepo.update)).map(_ => Ok("Ok"))
  }

  def clearRoutes() = Action.async { implicit request =>
    routeRepo.clearAll().map(_ => Ok("done"))
  }

}
