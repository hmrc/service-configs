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
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.connector.{ArtifactoryConnector, ConfigAsCodeConnector}
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.persistence.{AlertEnvironmentHandlerRepository, AlertHashStringRepository}
import uk.gov.hmrc.serviceconfigs.util.ZipUtil

import cats.data.EitherT

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AlertConfigSchedulerService @Inject()(
  alertEnvironmentHandlerRepository: AlertEnvironmentHandlerRepository,
  alertHashStringRepository        : AlertHashStringRepository,
  artifactoryConnector             : ArtifactoryConnector,
  configAsCodeConnector            : ConfigAsCodeConnector
)(implicit
  ec : ExecutionContext
) {
  private val logger = Logger(this.getClass)

  def updateConfigs(): Future[Unit] =
    (for {
      _             <- EitherT.pure[Future, Unit](logger.info("Starting"))
      currentHash   <- EitherT.right[Unit](artifactoryConnector.getLatestHash().map(_.getOrElse("")))
      previousHash  <- EitherT.right[Unit](alertHashStringRepository.findOne().map(_.map(_.hash).getOrElse("")))
      oHash          = Option.when(currentHash != previousHash)(currentHash)
      hash          <- EitherT.fromOption[Future](oHash, logger.info("No updates"))
      jsonZip       <- EitherT.right[Unit](artifactoryConnector.getSensuZip())
      sensuConfig   =  AlertConfigSchedulerService.processZip(jsonZip)
      codeZip       <- EitherT.right[Unit](configAsCodeConnector.streamAlertConfig())
      regex          = """src/main/scala/uk/gov/hmrc/alertconfig/configs/(.*).scala""".r
      blob           = "https://github.com/hmrc/alert-config/blob"
      repos          = AlertConfigSchedulerService.toRepos(sensuConfig)
      locations      = ZipUtil.findRepos(codeZip, repos, regex, blob)
      alertHandlers  = AlertConfigSchedulerService.toAlertEnvironmentHandler(sensuConfig, locations)
      _             <- EitherT.right[Unit](alertEnvironmentHandlerRepository.deleteAll())
      _             <- EitherT.right[Unit](alertEnvironmentHandlerRepository.insert(alertHandlers))
      _             <- EitherT.right[Unit](alertHashStringRepository.update(hash))
    } yield ()).merge
}

object AlertConfigSchedulerService {
  import uk.gov.hmrc.serviceconfigs.util.ZipUtil.NonClosableInputStream
  import java.util.zip.ZipInputStream
  import play.api.libs.json.{Json, Reads}

  case class AlertConfig(
    app     : String,
    handlers: Seq[String]
  )

  implicit val readsAlert: Reads[AlertConfig] =
    Json.reads[AlertConfig]

  case class Handler(command: String)

  implicit val readsHandler: Reads[Handler] =
    Json.reads[Handler]

  case class SensuConfig(
    alertConfigs: Seq[AlertConfig]          = Seq.empty,
    productionHandler: Map[String, Handler] = Map.empty
  )

  def processZip(zip: ZipInputStream): SensuConfig =
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(z => z != null)
      .foldLeft(SensuConfig())((config, entry) => {
        entry.getName match {
          case p if p.startsWith("target/output/configs/") && p.endsWith(".json") =>
            val json = Json.parse(new NonClosableInputStream(zip))
            config.copy(alertConfigs = config.alertConfigs :+ json.as[AlertConfig])
          case p if p.startsWith("target/output/handlers/aws_production") && p.endsWith(".json") =>
            config.copy(productionHandler = (Json.parse(new NonClosableInputStream(zip)) \ "handlers").as[Map[String, Handler]])
          case _ => config
        }
    })

  def toRepos(sensuConfig: SensuConfig): Seq[Repo] =
    for {
      alertConfig <- sensuConfig.alertConfigs
      serviceName <- alertConfig.app.split('.').headOption
    } yield Repo(serviceName)

  import uk.gov.hmrc.serviceconfigs.model.AlertEnvironmentHandler
  def toAlertEnvironmentHandler(sensuConfig: SensuConfig, locations: Seq[(Repo, String)]): Seq[AlertEnvironmentHandler] =
    for {
      alertConfig <- sensuConfig.alertConfigs
      serviceName <- alertConfig.app.split('.').headOption
      isProduction = alertConfig.handlers.exists(h => hasProductionHandler(sensuConfig.productionHandler, h))
      location    <- locations
                       .collect { case (Repo(name), location) if name == serviceName => location }
                       .headOption
    } yield AlertEnvironmentHandler(serviceName = serviceName, production = isProduction, location = location)

  private def hasProductionHandler(productionHandlers: Map[String, Handler], handler: String): Boolean =
    productionHandlers
      .get(handler)
      .exists(!_.command.contains("noop.rb"))
}
