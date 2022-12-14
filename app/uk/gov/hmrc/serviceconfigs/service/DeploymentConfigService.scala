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

package uk.gov.hmrc.serviceconfigs.service

import com.google.common.base.Charsets
import com.google.common.io.CharStreams
import org.yaml.snakeyaml.Yaml
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.connector.DeploymentConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.{DeploymentConfigRepository, YamlToBson}

import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import org.mongodb.scala.bson.BsonDocument

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

  def update(environment: Environment): Future[Unit] = {
    logger.info(s"getting deployment config for ${environment.asString}")
    for {
      zip      <- deploymentConfigConnector.getAppConfigZip(environment)
      toAdd    =  DeploymentConfigService.processZip(zip, environment)
      _        =  zip.close()
      _        <- deploymentConfigRepository.updateAll(toAdd)
      // remove records that dont have an app-config-$env file
      allNames <- deploymentConfigRepository.findAllNames(environment).map(_.toSet)
      toRemove =  toAdd.filterNot(c => allNames(c.getString("name").getValue)).map(_.getString("name").getValue).toSeq
      result   <- deploymentConfigRepository.delete(toRemove, environment)
      _        =  logger.info(s"updated ${toAdd.length}, removed ${toRemove.length} deployment configs in ${environment.asString}")
    } yield result
  }

  def findAll: Future[Seq[DeploymentConfig]] =
    deploymentConfigRepository.findAll

  def findAllForEnv(environment: Environment): Future[Seq[DeploymentConfig]] =
    deploymentConfigRepository.findAllForEnv(environment)

  def find(environment: Environment, serviceName: String): Future[Option[DeploymentConfig]] =
    deploymentConfigRepository.findByName(environment, serviceName)
}

object DeploymentConfigService extends Logging {
  import uk.gov.hmrc.serviceconfigs.util.ZipUtil.NonClosableInputStream
  import java.io.InputStreamReader
  import java.util.zip.ZipInputStream
  import scala.jdk.CollectionConverters._

  def processZip(zip: ZipInputStream, environment: Environment): Seq[BsonDocument] =
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .flatMap { file =>
        logger.debug(s"processing config file $file")
        file.getName match {
          case name if isAppConfig(name.toLowerCase) =>
            val shortName  = name.split("/").last.replace(".yaml", "")
            val yaml       = CharStreams.toString(new InputStreamReader(new NonClosableInputStream(zip), Charsets.UTF_8))
            val parsedYaml = new Yaml().load(yaml).asInstanceOf[java.util.LinkedHashMap[String, Object]].asScala
            modifyConfigKeys(parsedYaml, shortName, environment).flatMap(m => YamlToBson(m).toOption)
          case _ => None
        }
      }.toSeq

  private val ignoreList = Set("repository.yaml", "stale.yaml")

  def isAppConfig(filename: String) : Boolean =
    filename.endsWith(".yaml") && ignoreList.forall(i => !filename.endsWith(i))

  def parseConfig(filename: String, cfg: Map[String, String], environment: Environment): Option[DeploymentConfig] = {
    val name = filename.split("/").last.replace(".yaml", "")
    for {
      zone           <- cfg.get("zone")
      deploymentType <- cfg.get("type")
      slots          <- cfg.get("slots").map(_.toInt)
      instances      <- cfg.get("instances").map(_.toInt)
      artifactName    = cfg.get("artifact_name")
    } yield DeploymentConfig(name, artifactName, environment, zone, deploymentType, slots, instances)
  }

  def modifyConfigKeys(data: mutable.Map[String, Object], name: String, environment: Environment): Option[mutable.Map[String, Object]] = {
    import scala.jdk.CollectionConverters._
    val requiredKeys = Set("zone", "type", "slots", "instances")
    data
      .get("0.0.0")
      .map(_.asInstanceOf[java.util.LinkedHashMap[String, Object]].asScala)
      .flatMap(m => if (m.keySet.intersect(requiredKeys) == requiredKeys) Some(m) else None)
      .map { m =>
        m.remove("hmrc_config") // discard the app config, outside the scope of service
        m.remove("connector_config") // present in kafka-amqp-sink as an additional config block
        m.put("name", name)
        m.put("environment", environment.asString)
        m
      }
  }
}
