/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.libs.json.*
import play.api.libs.functional.syntax.*

case class RouteParameter(
  name        : String,
  typeName    : String,
  fixed       : Option[String] = None,
  default     : Option[String] = None,
  isPathParam : Boolean        = false,
  isQueryParam: Boolean        = false
)

object RouteParameter:
  val format: Format[RouteParameter] =
    ( (__ \ "name"        ).format[String]
    ~ (__ \ "typeName"    ).format[String]
    ~ (__ \ "fixed"       ).formatNullable[String]
    ~ (__ \ "default"     ).formatNullable[String]
    ~ (__ \ "isPathParam" ).format[Boolean]
    ~ (__ \ "isQueryParam").format[Boolean]
    )(apply, rp => Tuple.fromProductTyped(rp))

case class AppRoute(
  verb      : String,
  path      : String,
  controller: String,
  method    : String,
  parameters: Seq[RouteParameter] = Seq.empty,
  modifiers : Seq[String]         = Seq.empty
)

object AppRoute:
  val format: Format[AppRoute] =
    given Format[RouteParameter] = RouteParameter.format
    ( (__ \ "verb"      ).format[String]
    ~ (__ \ "path"      ).format[String]
    ~ (__ \ "controller").format[String]
    ~ (__ \ "method"    ).format[String]
    ~ (__ \ "parameters").format[Seq[RouteParameter]]
    ~ (__ \ "modifiers" ).format[Seq[String]]
    )(apply, ar => Tuple.fromProductTyped(ar))

case class UnevaluatedRoute(
  path  : String,
  router: String
)

object UnevaluatedRoute:
  val format: Format[UnevaluatedRoute] =
    ( (__ \ "path"  ).format[String]
    ~ (__ \ "router").format[String]
    )(apply, ur => Tuple.fromProductTyped(ur))

case class AppRoutes(
  service          : ServiceName,
  version          : Version,
  routes           : Seq[AppRoute],
  unevaluatedRoutes: Seq[UnevaluatedRoute] = Seq.empty
)

object AppRoutes:
  val format: Format[AppRoutes] =
    given Format[ServiceName]      = ServiceName.format
    given Format[Version]          = Version.format
    given Format[AppRoute]         = AppRoute.format
    given Format[UnevaluatedRoute] = UnevaluatedRoute.format
    ( (__ \ "service"          ).format[ServiceName]
    ~ (__ \ "version"          ).format[Version]
    ~ (__ \ "routes"           ).format[Seq[AppRoute]]
    ~ (__ \ "unevaluatedRoutes").formatWithDefault[Seq[UnevaluatedRoute]](Seq.empty)
    )(apply, ar => Tuple.fromProductTyped(ar))
