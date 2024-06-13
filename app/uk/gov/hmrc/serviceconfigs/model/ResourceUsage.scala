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
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import JsonUtil.ignoreOnWrite

import java.time.Instant


/**
  * `ResourceUsage` is a point-in-time snapshot of the slots/instances from a `DeploymentConfig`
  *
  * @param date The timestamp when the snapshot was taken
  * @param latest A flag indicating whether this snapshot is the most recent
  * @param deleted A flag indicating whether the upstream `DeploymentConfig` was deleted when this snapshot was taken
  */
final case class ResourceUsage(
  date       : Instant,
  serviceName: ServiceName,
  environment: Environment,
  slots      : Int,
  instances  : Int,
  latest     : Boolean,
  deleted    : Boolean
)

object ResourceUsage {
  val mongoFormat: Format[ResourceUsage] =
    ( (__ \ "date"        ).format[Instant](MongoJavatimeFormats.instantFormat)
    ~ (__ \ "serviceName" ).format[ServiceName](ServiceName.format)
    ~ (__ \ "environment" ).format[Environment](Environment.format)
    ~ (__ \ "slots"       ).format[Int]
    ~ (__ \ "instances"   ).format[Int]
    ~ (__ \ "latest"      ).format[Boolean]
    ~ (__ \ "deleted"     ).format[Boolean]
    )(ResourceUsage.apply, pt => Tuple.fromProductTyped(pt))

  val apiFormat: Format[ResourceUsage] =
    ( (__ \ "date"        ).format[Instant]
    ~ (__ \ "serviceName" ).format[ServiceName](ServiceName.format)
    ~ (__ \ "environment" ).format[Environment](Environment.format)
    ~ (__ \ "slots"       ).format[Int]
    ~ (__ \ "instances"   ).format[Int]
    ~ ignoreOnWrite[Boolean](__ \ "latest" )
    ~ ignoreOnWrite[Boolean](__ \ "deleted")
    )(ResourceUsage.apply, pt => Tuple.fromProductTyped(pt))
}
