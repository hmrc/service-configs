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

import cats.data.EitherT
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.connector.{ArtifactoryConnector, ConfigAsCodeConnector}
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, RepoName, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.{AlertEnvironmentHandlerRepository, LastHashRepository}
import uk.gov.hmrc.serviceconfigs.util.{YamlUtil, ZipUtil}

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AlertConfigService @Inject()(
  alertEnvironmentHandlerRepository: AlertEnvironmentHandlerRepository,
  lastHashRepository               : LastHashRepository,
  artifactoryConnector             : ArtifactoryConnector,
  configAsCodeConnector            : ConfigAsCodeConnector
)(using
  ec : ExecutionContext
):
  import AlertConfigService._

  private val logger = Logger(this.getClass)
  private val hashKey = "alert-config"

  def update(): Future[Unit] =
    (for
      _             <- EitherT.pure[Future, Unit](logger.info("Updating alert config"))
      currentHash   <- EitherT.right[Unit](artifactoryConnector.getLatestHash().map(_.getOrElse("")))
      previousHash  <- EitherT.right[Unit](lastHashRepository.getHash(hashKey).map(_.getOrElse("")))
      oHash         =  Option.when(currentHash != previousHash)(currentHash)
      hash          <- EitherT.fromOption[Future](oHash, logger.info("No updates, current alert config hash matches previous alert config hash"))
      jsonZip       <- EitherT.right[Unit](artifactoryConnector.getSensuZip())
      alertConfig   =  processZip(jsonZip)
      _             =  jsonZip.close()
      _             <- EitherT.fromOption[Future](alertConfig.serviceConfigs.headOption, logger.error("No service alert config found"))
      _             <- EitherT.fromOption[Future](alertConfig.productionHandlers.headOption , logger.error("No production handler config found"))
      _             =  logger.info(s"Alert config contains ${alertConfig.serviceConfigs.size} services and ${alertConfig.productionHandlers.size} production handlers")
      regex         =  """src/main/scala/uk/gov/hmrc/alertconfig/configs/(.*).scala""".r
      blob          =  "https://github.com/hmrc/alert-config/blob"
      repoNames     =  alertConfig.serviceConfigs.map(x => RepoName(x.serviceName.asString))
      _             =  logger.info(s"Found ${repoNames.size} repo names")
      codeZip       <- EitherT.right[Unit](configAsCodeConnector.streamAlertConfig())
      locations     =  ZipUtil.findRepos(codeZip, repoNames, regex, blob)
      _             =  codeZip.close()
      _             =  logger.info(s"Found ${locations.size} locations")
      alertHandlers =  toAlertEnvironmentHandler(alertConfig, locations)
      _             =  logger.info(s"Sorting ${alertHandlers.size} alert handlers")
      _             <- EitherT.right[Unit](alertEnvironmentHandlerRepository.putAll(alertHandlers))
      _             <- EitherT.right[Unit](lastHashRepository.update(hashKey, hash))
      _             =  logger.info("Successfully updated alert config and file hash")
     yield ()
    ).merge

  def findConfigs(): Future[Seq[AlertEnvironmentHandler]] =
    alertEnvironmentHandlerRepository.findAll()

  def findConfigByServiceName(serviceName: ServiceName): Future[Option[AlertEnvironmentHandler]] =
    alertEnvironmentHandlerRepository.findByServiceName(serviceName)

object AlertConfigService:
  import play.api.libs.json.{Json, JsValue, Reads, __}
  import play.api.libs.functional.syntax._

  case class PagerDutyKey(asString: String)
  private given Reads[PagerDutyKey] = summon[Reads[String]].map(PagerDutyKey.apply)


  case class ServiceConfig(
    serviceName  : ServiceName
  , pagerdutyKeys: Seq[PagerDutyKey]
  )

  private given Reads[ServiceConfig] =
    given Reads[ServiceName] = ServiceName.format
    given Reads[PagerDutyKey] = (__ \ "integrationKeyName").read[String].map(PagerDutyKey.apply _)
    ( (__ \ "service"  ).read[ServiceName]
    ~ (__ \ "pagerduty").read[Seq[PagerDutyKey]]
    )(ServiceConfig.apply _)

  case class Handler(command: String)

  private given Reads[Handler] =
    Reads.at[String]((__ \ "command")).map(Handler.apply)

  case class AlertConfig(
    serviceConfigs    : Seq[ServiceConfig]        = Seq.empty,
    productionHandlers: Map[PagerDutyKey, Handler] = Map.empty
  )

  def processZip(zip: ZipInputStream): AlertConfig =
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(AlertConfig()):
        (config, entry) =>
          entry.getName match
            case p if p.startsWith("target/output/services.yml")                 => config.copy(serviceConfigs     = (YamlUtil.fromYaml[JsValue](read(zip)) \ "services").as[Seq[ServiceConfig]])
            case p if p.startsWith("target/output/handlers/aws_production.json") => config.copy(productionHandlers = (Json.parse(ZipUtil.NonClosableInputStream(zip)) \ "handlers").as[Map[PagerDutyKey, Handler]])
            case _                                                               => config

  def toAlertEnvironmentHandler(alertConfig: AlertConfig, locations: Seq[(RepoName, String)]): Seq[AlertEnvironmentHandler] =
    for
      serviceConfig <- alertConfig.serviceConfigs
      location      <- locations.collectFirst:
                         case (name, location) if name.asString == serviceConfig.serviceName.asString => location
    yield AlertEnvironmentHandler(
      serviceName = serviceConfig.serviceName
    , production  = serviceConfig.pagerdutyKeys.exists(key => alertConfig.productionHandlers.get(key).exists(!_.command.contains("noop.rb")))
    , location    = location
    )

  private def read(in: ZipInputStream): String =
    val outputStream = java.io.ByteArrayOutputStream()
    val buffer       = new Array[Byte](4096)
    var bytesRead    = in.read(buffer)

    while (bytesRead != -1)
      outputStream.write(buffer, 0, bytesRead)
      bytesRead = in.read(buffer)

    String(outputStream.toByteArray, "UTF-8")
