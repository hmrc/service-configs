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

/*
 * Deployment config represents the non-application config from the app-config-env files.
 * It represents data about where and how a service is deployed
 */
case class DeploymentConfig(
  serviceName   : ServiceName,
  artefactName  : Option[ArtefactName],
  environment   : Environment,
  zone          : String,
  deploymentType: String,
  slots         : Int,
  instances     : Int,
  envVars       : Map[String, String],
  jvm           : Map[String, String]  // This should really be Seq[String], but for now keep the same as yaml since this defines how overrides work (not very well - but needs addressing by updating the yaml format)
)

object DeploymentConfig {
  private implicit val snf: Format[ServiceName] = ServiceName.format
  private implicit val anf: Format[ArtefactName] = ArtefactName.format

  val mongoFormat: Format[DeploymentConfig] =
    ( (__ \ "name"        ).format[ServiceName]
    ~ (__ \ "artefactName").formatNullable[ArtefactName]
    ~ (__ \ "environment" ).format[Environment](Environment.format)
    ~ (__ \ "zone"        ).format[String]
    ~ (__ \ "type"        ).format[String]
    ~ (__ \ "slots"       ).format[String].inmap[Int](_.toInt, _.toString)
    ~ (__ \ "instances"   ).format[String].inmap[Int](_.toInt, _.toString)
    ~ (__ \ "envVars"     ).format[Map[String, String]]
    ~ (__ \ "jvm"         ).format[Map[String, String]]
    )(DeploymentConfig.apply, unlift(DeploymentConfig.unapply))

  val apiFormat: Format[DeploymentConfig] =
    ( (__ \ "name"        ).format[ServiceName]
    ~ (__ \ "artefactName").formatNullable[ArtefactName]
    ~ (__ \ "environment" ).format[Environment](Environment.format)
    ~ (__ \ "zone"        ).format[String]
    ~ (__ \ "type"        ).format[String]
    ~ (__ \ "slots"       ).format[Int]
    ~ (__ \ "instances"   ).format[Int]
    ~ (__ \ "envVars"     ).format[Map[String, String]]
    ~ (__ \ "jvm"         ).format[Map[String, String]]
    )(DeploymentConfig.apply, unlift(DeploymentConfig.unapply))
}
