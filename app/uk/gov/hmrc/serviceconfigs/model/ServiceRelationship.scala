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

import play.api.libs.json._
import play.api.libs.functional.syntax._

case class ServiceRelationship(
  source: String,
  target: String
)

object ServiceRelationship {
  val serviceRelationshipFormat: OFormat[ServiceRelationship] =
    ( (__ \ "source").format[String]
    ~ (__ \ "target").format[String]
    )(ServiceRelationship.apply, unlift(ServiceRelationship.unapply))
}

case class ServiceRelationships(
  inboundServices : Seq[String],
  outboundServices: Seq[String]
)

object ServiceRelationships {
  val writes: OWrites[ServiceRelationships] =
    ( (__ \ "inboundServices").write[Seq[String]]
    ~ (__ \ "outboundServices").write[Seq[String]]
    )(unlift(ServiceRelationships.unapply))
}