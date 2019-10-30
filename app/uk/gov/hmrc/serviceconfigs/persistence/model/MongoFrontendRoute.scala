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

package uk.gov.hmrc.serviceconfigs.persistence.model

import java.time.{LocalDateTime, ZoneOffset}

import play.api.libs.json.{Format, Json, OFormat}
import uk.gov.hmrc.mongo.play.json.MongoJavatimeFormats

case class MongoFrontendRoute(
  service: String,
  frontendPath: String,
  backendPath: String,
  environment: String,
  routesFile: String,
  markerComments: Set[String]                      = Set.empty,
  shutterKillswitch: Option[MongoShutterSwitch]    = None,
  shutterServiceSwitch: Option[MongoShutterSwitch] = None,
  ruleConfigurationUrl: String                     = "",
  isRegex: Boolean                                 = false,
  updateDate: LocalDateTime                        = LocalDateTime.now(ZoneOffset.UTC)
)

case class MongoShutterSwitch(
  switchFile: String,
  statusCode: Option[Int]     = None,
  errorPage: Option[String]   = None,
  rewriteRule: Option[String] = None)

object MongoFrontendRoute {

  implicit val dateFormat: Format[LocalDateTime]               = MongoJavatimeFormats.localDateTimeFormats
  implicit val shutterSwitchFormat: Format[MongoShutterSwitch] = Json.format[MongoShutterSwitch]
  implicit val formats: OFormat[MongoFrontendRoute]            = Json.using[Json.WithDefaultValues].format[MongoFrontendRoute]

}
