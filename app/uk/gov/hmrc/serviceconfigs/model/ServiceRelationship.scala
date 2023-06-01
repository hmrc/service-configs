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
  source: ServiceName,
  target: ServiceName
)

object ServiceRelationship {
  val serviceRelationshipFormat: OFormat[ServiceRelationship] = {
    implicit val snf = ServiceName.format
    ( (__ \ "source").format[ServiceName]
    ~ (__ \ "target").format[ServiceName]
    )(ServiceRelationship.apply, unlift(ServiceRelationship.unapply))
  }
}

case class ServiceRelationships(
  inboundServices : Set[ServiceName],
  outboundServices: Set[ServiceName]
)

object ServiceRelationships {
  val writes: OWrites[ServiceRelationships] = {
    implicit val snf = ServiceName.format
    ( (__ \ "inboundServices").write[Set[ServiceName]]
    ~ (__ \ "outboundServices").write[Set[ServiceName]]
    )(unlift(ServiceRelationships.unapply))
  }
}
