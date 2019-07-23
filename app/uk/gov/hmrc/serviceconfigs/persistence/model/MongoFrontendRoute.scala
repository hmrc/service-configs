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

import org.joda.time.DateTime
import play.api.libs.json.{Format, Json, OFormat}
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats


case class MongoFrontendRoute(
 service     : String,
 frontendPath: String,
 backendPath : String,
 environment : String,
 shutterKillswitch: Option[MongoShutterKillswitch] = None,
 shutterServiceSwitch: Option[MongoShutterServiceSwitch] = None,
 ruleConfigurationUrl: String = "",
 isRegex     : Boolean = false,
 updateDate  : DateTime = DateTime.now
)

sealed trait MongoShutterSwitch {
  def statusCode: Int
}

case class MongoShutterKillswitch(statusCode: Int) extends MongoShutterSwitch

case class MongoShutterServiceSwitch(statusCode: Int, switchFile: String, errorPage: String) extends MongoShutterSwitch


object MongoFrontendRoute {

  implicit val dateFormat: Format[DateTime]         = ReactiveMongoFormats.dateTimeFormats
  implicit val killswitchFormat: Format[MongoShutterKillswitch] = Json.format[MongoShutterKillswitch]
  implicit val serviceSwitchFormat: Format[MongoShutterServiceSwitch] = Json.format[MongoShutterServiceSwitch]
  implicit val formats: OFormat[MongoFrontendRoute] = Json.using[Json.WithDefaultValues].format[MongoFrontendRoute]

}
