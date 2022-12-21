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

package uk.gov.hmrc.serviceconfigs.model

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class AdminFrontendRoute(
  service : String
, route   : String
, allow   : Map[String, List[String]]
, location: String
)

object AdminFrontendRoute {
  val format: Format[AdminFrontendRoute] =
    ( (__ \ "service" ).format[String]
    ~ (__ \ "route"   ).format[String]
    ~ (__ \ "allow"   ).format[Map[String, List[String]]]
    ~ (__ \ "location").format[String]
    )(AdminFrontendRoute.apply, unlift(AdminFrontendRoute.unapply))
}