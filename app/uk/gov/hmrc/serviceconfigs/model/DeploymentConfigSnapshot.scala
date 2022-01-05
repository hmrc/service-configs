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
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant

/**
  * A `DeploymentConfigSnapshot` represents a point-in-time snapshot of a `DeploymentConfig`
  *
  * @param date The timestamp when the snapshot was taken
  * @param serviceName The name of the service
  * @param environment The environment the service is deployed in
  * @param deploymentConfig If present, the snapshot of the `DeploymentConfig`, or else indicates the removal of a service from an environment
  */
final case class DeploymentConfigSnapshot(
  date: Instant,
  serviceName: String,
  environment: Environment,
  deploymentConfig: Option[DeploymentConfig]
)

object DeploymentConfigSnapshot {

  val mongoFormat: Format[DeploymentConfigSnapshot] =
    (   (__ \ "date"             ).format(MongoJavatimeFormats.instantFormat)
      ~ (__ \ "serviceName"      ).format[String]
      ~ (__ \ "environment"      ).format[Environment](Environment.format)
      ~ (__ \ "deploymentConfig" ).formatNullable[DeploymentConfig](DeploymentConfig.mongoFormat)
      ) (DeploymentConfigSnapshot.apply, unlift(DeploymentConfigSnapshot.unapply))

  val apiFormat: Format[DeploymentConfigSnapshot] =
    (   (__ \ "date"             ).format[Instant]
      ~ (__ \ "serviceName"      ).format[String]
      ~ (__ \ "environment"      ).format[Environment](Environment.format)
      ~ (__ \ "deploymentConfig" ).formatNullable[DeploymentConfig](DeploymentConfig.apiFormat)
      ) (DeploymentConfigSnapshot.apply, unlift(DeploymentConfigSnapshot.unapply))
}
