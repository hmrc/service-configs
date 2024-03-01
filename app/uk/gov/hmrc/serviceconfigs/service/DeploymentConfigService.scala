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

import com.google.common.base.Charsets
import com.google.common.io.CharStreams
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.connector.DeploymentConfigConnector
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.{ArtefactName, DeploymentConfig, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigRepository
import uk.gov.hmrc.serviceconfigs.util.YamlUtil

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigService @Inject()(
                                         deploymentConfigConnector : DeploymentConfigConnector,
                                         deploymentConfigRepository: DeploymentConfigRepository
)(implicit
  ec: ExecutionContext
) extends Logging {

  def updateAll(): Future[Unit] = {
    logger.info("Updating all environments...")
    Future.traverse(Environment.values)(update).map(_ => ())
  }

  def update(environment: Environment): Future[Unit] =
    for {
      _     <- Future.successful(logger.info(s"Getting Deployment Configs for ${environment.asString}"))
      zip   <- deploymentConfigConnector.getAppConfigZip(environment)
      items  = DeploymentConfigService.processZip(zip, environment)
      _      = zip.close()
      _      = logger.info(s"Inserting ${items.size} Deployment Configs into mongo for ${environment.asString}")
      count <- deploymentConfigRepository.replaceEnv(environment, items)
      _      = logger.info(s"Inserted $count Deployment Configs into mongo for ${environment.asString}")
    } yield ()

  def find(environments: Seq[Environment], serviceName: Option[ServiceName], repos: Option[Seq[Repo]]): Future[Seq[DeploymentConfig]] =
      deploymentConfigRepository.find(environments, serviceName, repos)
}

object DeploymentConfigService extends Logging {
  import uk.gov.hmrc.serviceconfigs.util.ZipUtil.NonClosableInputStream

  import java.io.InputStreamReader
  import java.util.zip.ZipInputStream

  def processZip(zip: ZipInputStream, environment: Environment): Seq[DeploymentConfig] =
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .filter(file => isAppConfig(file.getName.toLowerCase))
      .flatMap(file =>
        toDeploymentConfig(
          fileName    = file.getName
        , fileContent = CharStreams.toString(new InputStreamReader(new NonClosableInputStream(zip), Charsets.UTF_8))
        , environment = environment
        )
      ).toSeq

  private val ignoreList = Set("repository.yaml", "stale.yaml")

  private [service] def isAppConfig(filename: String) : Boolean =
    filename.endsWith(".yaml") && ignoreList.forall(i => !filename.endsWith(i))

  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  private def ignore[A](a: A): Reads[A] =
    Reads[A](_ => JsSuccess(a))

  private def yamlDeploymentConfigReads(serviceName: ServiceName, environment: Environment): Reads[DeploymentConfig] =
    ( ignore(serviceName)
      // reads US spelling from config but we use UK version
    ~ (__ \ "artifact_name").readNullable[ArtefactName](ArtefactName.format)
    ~ ignore(environment)
    ~ (__ \ "zone"         ).read[String]
    ~ (__ \ "type"         ).read[String]
    ~ (__ \ "slots"        ).read[Int]
    ~ (__ \ "instances"    ).read[Int]
    ) (DeploymentConfig.apply _)

  private [service] def toDeploymentConfig(fileName: String, fileContent: String, environment: Environment): Option[DeploymentConfig] =
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
