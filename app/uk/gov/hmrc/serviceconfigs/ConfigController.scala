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
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.serviceconfigs.ConfigService._
import uk.gov.hmrc.serviceconfigs.ConfigService.BaseConfig

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class ConfigController @Inject()(
  configService: ConfigService,
  mcc: MessagesControllerComponents
) extends BackendController(mcc) {

  implicit val configEntryReads = Json.reads[ConfigEntry]
  implicit val configEntryWrites = Json.writes[ConfigEntry]

  implicit val applicationConfReads = Json.reads[ApplicationConf]
  implicit val applicationConfWrites = Json.writes[ApplicationConf]
  implicit val baseConfigReads = Json.reads[BaseConfig]
  implicit val baseConfigWrites = Json.writes[BaseConfig]
  implicit val appConfigReads = Json.reads[AppConfig]
  implicit val appConfigWrites = Json.writes[AppConfig]
  implicit val appConfigCommonFixedReads = Json.reads[AppConfigCommonFixed]
  implicit val appConfigCommonFixedWrites = Json.writes[AppConfigCommonFixed]
  implicit val appConfigCommonOverridableReads = Json.reads[AppConfigCommonOverridable]
  implicit val appConfigCommonOverridableWrites = Json.writes[AppConfigCommonOverridable]

  implicit val configSourceReads = Json.reads[ConfigSource]
  implicit val configSourceWrites = Json.writes[ConfigSource]

  implicit val EnvironmentReads = Json.reads[Environment]
  implicit val EnvironmentWrites = Json.writes[Environment]

  implicit val EnvironmentConfigSourceReads = Json.reads[EnvironmentConfigSource]
  implicit val EnvironmentConfigSourceWrites = Json.writes[EnvironmentConfigSource]


  def serviceConfig(serviceName: String) = Action.async { implicit request =>
    configService.configByEnvironment(serviceName).map {e =>
        Ok(Json.toJson(e))
    }

  }

}