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
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, RepoName, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.{AlertEnvironmentHandlerRepository, LastHashRepository}
import uk.gov.hmrc.serviceconfigs.util.ZipUtil

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AlertConfigService @Inject()(
  alertEnvironmentHandlerRepository: AlertEnvironmentHandlerRepository,
  lastHashRepository               : LastHashRepository,
  artifactoryConnector             : ArtifactoryConnector,
  configAsCodeConnector            : ConfigAsCodeConnector
)(implicit
  ec : ExecutionContext
) {
  import AlertConfigService._

  private val logger = Logger(this.getClass)
  private val hashKey = "alert-config"

  def update(): Future[Unit] =
    (for {
      _             <- EitherT.pure[Future, Unit](logger.info("Starting"))
      currentHash   <- EitherT.right[Unit](artifactoryConnector.getLatestHash().map(_.getOrElse("")))
      previousHash  <- EitherT.right[Unit](lastHashRepository.getHash(hashKey).map(_.getOrElse("")))
      oHash         =  Option.when(currentHash != previousHash)(currentHash)
      hash          <- EitherT.fromOption[Future](oHash, logger.info("No updates"))
      jsonZip       <- EitherT.right[Unit](artifactoryConnector.getSensuZip())
      sensuConfig   =  processZip(jsonZip)
      _             =  jsonZip.close()
      regex         =  """src/main/scala/uk/gov/hmrc/alertconfig/configs/(.*).scala""".r
      blob          =  "https://github.com/hmrc/alert-config/blob"
      repoNames     =  toRepoNames(sensuConfig)
      codeZip       <- EitherT.right[Unit](configAsCodeConnector.streamAlertConfig())
      locations     =  ZipUtil.findRepos(codeZip, repoNames, regex, blob)
      _             =  codeZip.close()
      alertHandlers =  toAlertEnvironmentHandler(sensuConfig, locations)
      _             <- EitherT.right[Unit](alertEnvironmentHandlerRepository.putAll(alertHandlers))
      _             <- EitherT.right[Unit](lastHashRepository.update(hashKey, hash))
     } yield ()
    ).merge

  def findConfigs(): Future[Seq[AlertEnvironmentHandler]] =
    alertEnvironmentHandlerRepository.findAll()

  def findConfigByServiceName(serviceName: ServiceName): Future[Option[AlertEnvironmentHandler]] =
    alertEnvironmentHandlerRepository.findByServiceName(serviceName)
}

object AlertConfigService {
  import play.api.libs.json.{Json, Reads, __}
  import play.api.libs.functional.syntax._

  case class AlertConfig(
    app     : String,
    handlers: Seq[String]
  )

  private implicit val alertsConfigReads: Reads[AlertConfig] =
    ( (__ \ "app"     ).read[String]
    ~ (__ \ "handlers").read[Seq[String]]
    )(AlertConfig.apply _)

  case class Handler(command: String)

  private implicit val readsHandler: Reads[Handler] =
    Reads.at[String]((__ \ "command")).map(Handler.apply)

  case class SensuConfig(
    alertConfigs     : Seq[AlertConfig]     = Seq.empty,
    productionHandler: Map[String, Handler] = Map.empty
  )

  def processZip(zip: ZipInputStream): SensuConfig =
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(SensuConfig()){ (config, entry) =>
        entry.getName match {
          case p if p.startsWith("target/output/configs/") && p.endsWith(".json") =>
            val json = Json.parse(new ZipUtil.NonClosableInputStream(zip))
            config.copy(alertConfigs = config.alertConfigs :+ json.as[AlertConfig])
          case p if p.startsWith("target/output/handlers/aws_production") && p.endsWith(".json") =>
            config.copy(productionHandler = (Json.parse(new ZipUtil.NonClosableInputStream(zip)) \ "handlers").as[Map[String, Handler]])
          case _ => config
        }
    }

  def toRepoNames(sensuConfig: SensuConfig): Seq[RepoName] =
    for {
      alertConfig <- sensuConfig.alertConfigs
      serviceName <- alertConfig.app.split('.').headOption.map(RepoName(_))
    } yield serviceName


  def toAlertEnvironmentHandler(sensuConfig: SensuConfig, locations: Seq[(RepoName, String)]): Seq[AlertEnvironmentHandler] =
    for {
      alertConfig  <- sensuConfig.alertConfigs
      serviceName  <- alertConfig.app.split('.').headOption.map(ServiceName.apply)
      isProduction =  alertConfig.handlers.exists(h => hasProductionHandler(sensuConfig.productionHandler, h))
      location     <- locations.collectFirst { case (name, location) if name.asString == serviceName.asString => location }
    } yield AlertEnvironmentHandler(serviceName = serviceName, production = isProduction, location = location)

  private def hasProductionHandler(productionHandlers: Map[String, Handler], handler: String): Boolean =
    productionHandlers
      .get(handler)
      .exists(!_.command.contains("noop.rb"))
}
