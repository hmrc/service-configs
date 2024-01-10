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


case class DeploymentConfigV2(
                               serviceName: ServiceName,
                               configs: Seq[DeploymentEnvironment],
                               count: Int
                             )

object DeploymentConfigV2 {
  private implicit val snf = ServiceName.format

  implicit val format: Format[DeploymentConfigV2] =
    ((__ \ "_id").format[ServiceName]
      ~ (__ \ "configs").format[Seq[DeploymentEnvironment]]
      ~ (__ \ "count").format[Int]
      )(DeploymentConfigV2.apply, unlift(DeploymentConfigV2.unapply))

}


case class DeploymentEnvironment (
                                   artifactName: Option[String],
                                   environment: Environment,
                                   zone: String,
                                   deploymentType: String,
                                   slots: Int,
                                   instances: Int
                                 )

object DeploymentEnvironment {

    implicit val format: Format[DeploymentEnvironment] =
      ((__ \ "artifactName").formatNullable[String]
        ~ (__ \ "environment").format[Environment](Environment.format)
        ~ (__ \ "zone").format[String]
        ~ (__ \ "type").format[String]
        ~ (__ \ "slots").format[Int]
        ~ (__ \ "instances").format[Int]
        )(DeploymentEnvironment.apply, unlift(DeploymentEnvironment.unapply))
}
