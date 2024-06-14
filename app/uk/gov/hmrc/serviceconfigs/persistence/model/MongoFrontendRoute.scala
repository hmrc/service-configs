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

package uk.gov.hmrc.serviceconfigs.persistence.model

import java.time.Instant

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}

case class MongoFrontendRoute(
  service             : ServiceName,
  frontendPath        : String,
  backendPath         : String,
  environment         : Environment,
  routesFile          : String,
  markerComments      : Set[String]                = Set.empty,
  shutterKillswitch   : Option[MongoShutterSwitch] = None,
  shutterServiceSwitch: Option[MongoShutterSwitch] = None,
  ruleConfigurationUrl: String                     = "",
  isRegex             : Boolean                    = false,
  updateDate          : Instant                    = Instant.now()
)

case class MongoShutterSwitch(
  switchFile : String,
  statusCode : Option[Int]    = None,
  errorPage  : Option[String] = None,
  rewriteRule: Option[String] = None
)

object MongoFrontendRoute {
  private given Format[MongoShutterSwitch] =
    ( (__ \ "switchFile").format[String]
    ~ (__ \ "statusCode").formatNullable[Int]
    ~ (__ \ "errorPage").formatNullable[String]
    ~ (__ \ "rewriteRule").formatNullable[String]
    )(MongoShutterSwitch.apply, pt => Tuple.fromProductTyped(pt))

  val format: Format[MongoFrontendRoute] = {
    ( (__ \ "service"             ).format[ServiceName](ServiceName.format)
    ~ (__ \ "frontendPath"        ).format[String]
    ~ (__ \ "backendPath"         ).format[String]
    ~ (__ \ "environment"         ).format[Environment](Environment.format)
    ~ (__ \ "routesFile"          ).format[String]
    ~ (__ \ "markerComments"      ).formatWithDefault[Set[String]](Set.empty)
    ~ (__ \ "shutterKillswitch"   ).formatNullable[MongoShutterSwitch]
    ~ (__ \ "shutterServiceSwitch").formatNullable[MongoShutterSwitch]
    ~ (__ \ "ruleConfigurationUrl").format[String]
    ~ (__ \ "isRegex"             ).formatWithDefault[Boolean](false)
    ~ (__ \ "updateDate"          ).formatWithDefault[Instant](Instant.now())(MongoJavatimeFormats.instantFormat)
    )(MongoFrontendRoute.apply, pt => Tuple.fromProductTyped(pt))
  }
}
