/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.libs.json.{JsString, JsValue}

enum RouteType(val asString: String):
  case AdminFrontend extends RouteType("adminfrontend")
  case Devhub        extends RouteType("devhub")
  case Frontend      extends RouteType("frontend")

object RouteType:
  def parse(s: String): Option[RouteType] =
    values.find(_.asString == s)

  def writes(o: RouteType): JsValue =
    JsString(o.asString)
