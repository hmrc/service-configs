/*
 * Copyright 2020 HM Revenue & Customs
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

import cats.implicits._
import javax.inject.Inject
import play.api.libs.json.{Format, JsError, Json, Reads}
import play.api.mvc.{Action, AnyContent, BodyParser, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, SlugDependency, SlugInfo, Version}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, FrontendRouteRepository, SlugInfoRepository}

import scala.concurrent.ExecutionContext

class IntegrationTestController @Inject()(
  routeRepo           : FrontendRouteRepository,
  dependencyConfigRepo: DependencyConfigRepository,
  slugRepo            : SlugInfoRepository,
  mcc                 : MessagesControllerComponents
)(implicit ec: ExecutionContext
) extends BackendController(mcc) {

  import MongoFrontendRoute.formats

  implicit val dependencyConfigReads: Reads[DependencyConfig] =
    Json.using[Json.WithDefaultValues].reads[DependencyConfig]

  implicit val slugReads: Reads[SlugInfo] = {
    implicit val sdr: Reads[SlugDependency] = Json.using[Json.WithDefaultValues].reads[SlugDependency]
    implicit val vd: Format[Version]        = Version.apiFormat
    Json.using[Json.WithDefaultValues].reads[SlugInfo]
  }

  def validateJson[A: Reads]: BodyParser[A] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addRoutes(): Action[List[MongoFrontendRoute]] =
    Action.async(validateJson[List[MongoFrontendRoute]]) {
      implicit request =>
        request.body
          .traverse(routeRepo.update)
          .map(_ => Ok("Ok"))
    }

  def clearRoutes(): Action[AnyContent] =
    Action.async {
      routeRepo.clearAll()
        .map(_ => Ok("done"))
    }

  def addSlugDependencyConfigs(): Action[List[DependencyConfig]] =
    Action.async(validateJson[List[DependencyConfig]]) { implicit request =>
      request.body
        .traverse(dependencyConfigRepo.add)
        .map(_ => Ok("Done"))
    }

  def deleteSlugDependencyConfigs(): Action[AnyContent] =
    Action.async {
      dependencyConfigRepo.clearAllData.map(_ => Ok("Done"))
    }

  def addSlugs(): Action[List[SlugInfo]] =
    Action.async(validateJson[List[SlugInfo]]) { implicit request =>
      request.body
        .traverse(slugRepo.add)
        .map(_ => Ok("Done"))
    }

  def deleteSlugs(): Action[AnyContent] =
    Action.async {
      slugRepo.clearAll().map(_ => Ok("Done"))
    }
}
