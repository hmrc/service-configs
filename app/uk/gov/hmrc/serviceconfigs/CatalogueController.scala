/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs

import javax.inject.{Inject, Singleton}
import play.api.http.FileMimeTypes
import play.api.i18n.{Langs, MessagesApi}
import play.api.libs.json.{Format, Json}
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.{BackendController, BaseController}
import uk.gov.hmrc.serviceconfigs.ConfigService._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class CatalogueController @Inject()(
  configService: ConfigService,
  mcc: MessagesControllerComponents
) extends BackendController(mcc) {

//  implicit val configByEnvironmentReads = Json.reads[ConfigByEnvironment]
//  implicit val configEntryWrites = Json.writes[ConfigEntry]
//  implicit val configSourceBaseWrites = Json.writes[BaseConfig]
//  implicit val environmentWrites = Json.writes[Base]
//  implicit val environmentConfigSourceWrites = Json.writes[EnvironmentConfigSource]
//  implicit val configByEnvironmentWrites = Json.writes[ConfigByEnvironment]
//
//  implicit val configEntryFormat: Format[ConfigEntry] = Json.format[ConfigEntry]
//  implicit val configSourceFormat: Format[ConfigSource] = Json.format[ConfigSource]
//  implicit val environmentFormat: Format[Environment] = Json.format[Environment]
//  implicit val environmentConfigSourceFormat: Format[EnvironmentConfigSource] = Json.format[EnvironmentConfigSource]
//  implicit val configByEnvironmentFormat: Format[ConfigByEnvironment] = Json.format[ConfigByEnvironment]

  def serviceConfig(serviceName: String) = Action.async { implicit request =>
    configService.configByEnvironment(serviceName).map {e =>
        Ok("{}")

    }

  }

}