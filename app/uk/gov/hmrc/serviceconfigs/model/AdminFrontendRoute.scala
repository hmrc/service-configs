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

package uk.gov.hmrc.serviceconfigs.model

import play.api.libs.functional.syntax._
import play.api.libs.json._

case class AdminFrontendRoute(
  serviceName: ServiceName
, route      : String
, allow      : Map[Environment, List[String]]
, location   : String
)

object AdminFrontendRoute:
  val format: Format[AdminFrontendRoute] =
    given Format[ServiceName] = ServiceName.format
    given Format[Map[Environment, List[String]]] =
        Format(
          Reads
            .of[Map[String, List[String]]]
            .map(_.map { case (k, v) => (Environment.parse(k).getOrElse(sys.error(s"Invalid Environment: $k")), v) })
        , Writes
            .apply(xs => Json.toJson(xs.map { case (k, v) => k.asString -> v }))
        )

    ( (__ \ "service" ).format[ServiceName]
    ~ (__ \ "route"   ).format[String]
    ~ (__ \ "allow"   ).format[Map[Environment, List[String]]]
    ~ (__ \ "location").format[String]
    )(AdminFrontendRoute.apply, pt => Tuple.fromProductTyped(pt))
