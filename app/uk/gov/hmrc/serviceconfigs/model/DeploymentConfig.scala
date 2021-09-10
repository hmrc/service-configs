/*
 * Copyright 2021 HM Revenue & Customs
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
import play.api.libs.json.{Format, Reads, Writes, __}

/*
 * Deployment config represents the non-application config from the app-config-env files.
 * It represents data about where and how a service is deployed
 */
case class DeploymentConfig(name:           String,
                            artifactName:   Option[String],
                            environment:    Environment,
                            zone:           String,
                            deploymentType: String,
                            slots:          Int,
                            instances:      Int)

object DeploymentConfig {
  val mongoFormat: Format[DeploymentConfig] = {
    (   (__ \ "name"        ).format[String]
      ~ (__ \ "artifactName").formatNullable[String]
      ~ (__ \ "environment" ).format[Environment](Environment.format)
      ~ (__ \ "zone"        ).format[String]
      ~ (__ \ "type"        ).format[String]
      ~ (__ \ "slots"       ).format[String].inmap[Int](_.toInt, _.toString)
      ~ (__ \ "instances"   ).format[String].inmap[Int](_.toInt, _.toString)
      ) (DeploymentConfig.apply, unlift(DeploymentConfig.unapply))
  }

  val apiWrites: Writes[DeploymentConfig] = {
    (   (__ \ "name"        ).write[String]
      ~ (__ \ "artifactName").writeNullable[String]
      ~ (__ \ "environment" ).write[Environment](Environment.format)
      ~ (__ \ "zone"        ).write[String]
      ~ (__ \ "type"        ).write[String]
      ~ (__ \ "slots"       ).write[Int]
      ~ (__ \ "instances"   ).write[Int]
      )(unlift(DeploymentConfig.unapply))
  }
}