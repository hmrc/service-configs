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
import play.api.libs.json.{Format, JsError, Json, Reads}
import play.api.mvc.{Action, AnyContent, BodyParser, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, SlugDependency, SlugInfo, Version}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, FrontendRouteRepository, SlugConfigurationInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

class IntegrationTestController @Inject()(
  routeRepo: FrontendRouteRepository,
  dependencyConfigRepo: DependencyConfigRepository,
  slugRepo: SlugConfigurationInfoRepository,
  mcc: MessagesControllerComponents)(implicit ec: ExecutionContext)
    extends BackendController(mcc) {

  import MongoFrontendRoute.formats
  implicit val dependencyConfigReads: Reads[DependencyConfig] =
    Json.using[Json.WithDefaultValues].reads[DependencyConfig]
  implicit val slugReads: Reads[SlugInfo] = {
    implicit val sdr: Reads[SlugDependency] = Json.using[Json.WithDefaultValues].reads[SlugDependency]
    implicit val vd: Format[Version]        = Version.apiFormat
    Json.using[Json.WithDefaultValues].reads[SlugInfo]
  }

  def validateJson[A: Reads]: BodyParser[A] =
    parse.json.validate(_.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e))))

  def addRoutes(): Action[Seq[MongoFrontendRoute]] = Action.async(validateJson[Seq[MongoFrontendRoute]]) {
    implicit request =>
      Future.sequence(request.body.map(routeRepo.update)).map(_ => Ok("Ok"))
  }

  def clearRoutes(): Action[AnyContent] = Action.async { implicit request =>
    routeRepo.clearAll().map(_ => Ok("done"))
  }

  def addSlugDependencyConfigs(): Action[Seq[DependencyConfig]] =
    Action.async(validateJson[Seq[DependencyConfig]]) { implicit request =>
      Future
        .sequence(request.body.map(dependencyConfigRepo.add))
        .map(_ => Ok("Done"))
    }

  def deleteSlugDependencyConfigs(): Action[AnyContent] =
    Action.async { implicit request =>
      dependencyConfigRepo.clearAllData.map(_ => Ok("Done"))
    }

  def addSlugs(): Action[Seq[SlugInfo]] =
    Action.async(validateJson[Seq[SlugInfo]]) { implicit request =>
      Future
        .sequence(request.body.map(slugRepo.add))
        .map(_ => Ok("Done"))
    }

  def deleteSlugs(): Action[AnyContent] =
    Action.async { implicit request =>
      slugRepo.clearAll().map(_ => Ok("Done"))
    }

}
