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

package uk.gov.hmrc.serviceconfigs.service

import play.api.Logging
import uk.gov.hmrc.serviceconfigs.model.{ArtefactName, DeploymentConfig, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.util.YamlUtil

object DeploymentConfigService extends Logging {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  private def yamlDeploymentConfigReads(serviceName: ServiceName, environment: Environment): Reads[DeploymentConfig] =
    ( Reads.pure(serviceName)
    ~ (__ \ "artifact_name").readNullable[ArtefactName](ArtefactName.format)
    ~ Reads.pure(environment)
    ~ (__ \ "zone"         ).read[String]
    ~ (__ \ "type"         ).read[String]
    ~ (__ \ "slots"        ).read[Int]
    ~ (__ \ "instances"    ).read[Int]
    )(DeploymentConfig.apply _)

  def toDeploymentConfig(fileName: String, fileContent: String, environment: Environment): Option[DeploymentConfig] =
    scala.util.Try {
      logger.debug(s"processing config file $fileName")
      YamlUtil
        .fromYaml[Map[String, JsValue]](fileContent)
        .get("0.0.0")
        .map(_.as[DeploymentConfig](yamlDeploymentConfigReads(ServiceName(fileName.split("/").last.replace(".yaml", "")), environment)))
    }.fold(
      err => { logger.error(s"Could not process file $fileName while looking for deployment config" , err); None }
    , res => res
    )
}
