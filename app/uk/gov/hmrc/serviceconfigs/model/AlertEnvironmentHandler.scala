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
import play.api.libs.json.{Format, __}

case class AlertEnvironmentHandler(
  serviceName: ServiceName,
  production : Boolean,
  location   : String
)

object AlertEnvironmentHandler {
  val format: Format[AlertEnvironmentHandler] = {
    given Format[ServiceName] = ServiceName.format
    ( (__ \ "serviceName").format[ServiceName]
    ~ (__ \ "production" ).format[Boolean]
    ~ (__ \ "location"   ).format[String]
    )(AlertEnvironmentHandler.apply, pt => Tuple.fromProductTyped(pt))
  }
}
