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
import uk.gov.hmrc.serviceconfigs.parser.ConfigValue

object DeploymentConfigService extends Logging {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  private def readMap(path: JsPath): Reads[Map[String, String]] =
    path.readWithDefault[Map[String, JsValue]](Map.empty)
      .map(_.view.mapValues {
        case JsString(v)  => ConfigValue.suppressEncryption(v)
        case JsBoolean(b) => b.toString
        case JsNumber(n)  => n.toString
        case other        => other.toString // not expected
      }.toMap)

  private def yamlDeploymentConfigReads(
    serviceName: ServiceName,
    environment: Environment,
    applied    : Boolean
  ): Reads[DeploymentConfig] =
    ( Reads.pure(serviceName)
    ~ (__ \ "artifact_name").readNullable[ArtefactName](ArtefactName.format)
    ~ Reads.pure(environment)
    ~ (__ \ "zone"         ).read[String]
    ~ (__ \ "type"         ).read[String]
    ~ (__ \ "slots"        ).read[Int]
    ~ (__ \ "instances"    ).read[Int]
    ~ readMap(__ \ "environment")
    ~ readMap(__ \ "jvm"        )
    ~ Reads.pure(applied)
    )(DeploymentConfig.apply _)

  def toDeploymentConfig(
    serviceName: ServiceName,
    environment: Environment,
    applied    : Boolean,
    fileContent: String
  ): Option[DeploymentConfig] =
    scala.util.Try {
      YamlUtil
        .fromYaml[Map[String, JsValue]](fileContent)
        .get("0.0.0")
        .map(_.as[DeploymentConfig](yamlDeploymentConfigReads(serviceName, environment, applied)))
    }.fold(
      err => { logger.error(s"Could not process the deployment config for $serviceName, $environment" , err); None }
    , res => res
    )
}
