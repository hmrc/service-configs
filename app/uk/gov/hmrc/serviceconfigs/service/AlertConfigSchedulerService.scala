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
import play.api.libs.json.{Json, OFormat}
import uk.gov.hmrc.serviceconfigs.connector.ArtifactoryConnector
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, LastHash}
import uk.gov.hmrc.serviceconfigs.persistence.{AlertEnvironmentHandlerRepository, AlertHashStringRepository}
import uk.gov.hmrc.serviceconfigs.service.AlertConfigSchedulerService.{processSensuConfig, processZip}

import java.io.{FilterInputStream, InputStream}
import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class AlertConfigSchedulerService @Inject()(alertEnvironmentHandlerRepository: AlertEnvironmentHandlerRepository,
                                            alertHashStringRepository: AlertHashStringRepository,
                                            artifactoryConnector: ArtifactoryConnector)(implicit val ec : ExecutionContext) {

  private val logger = Logger(this.getClass)

  def updateConfigs(): Future[Unit] = {

    logger.info("Starting")

    (for {
      latestHashString       <- artifactoryConnector.getLatestHash().map(x => x.getOrElse(""))
      previousHashString     <- alertHashStringRepository.findOne().map(x => x.getOrElse(LastHash("")).hash)
      maybeHashString        =  if (!latestHashString.equals(previousHashString)) Option(latestHashString) else None
    } yield maybeHashString).flatMap {
      case Some(hashString) =>
        for {
          zip           <- artifactoryConnector.getSensuZip()
          sensuConfig   =  processZip(zip)
          alertHandlers =  processSensuConfig(sensuConfig)
          _             <- alertEnvironmentHandlerRepository.deleteAll()
          _             <- alertEnvironmentHandlerRepository.insert(alertHandlers)
          ok            <- alertHashStringRepository.update(hashString)
        } yield ok

      case None =>
        logger.info("No updates")
        Future.successful(())
    }

  }
}

case class AlertConfig(
  app: String,
  handlers: Seq[String]
)

object AlertConfig {
  implicit val formats: OFormat[AlertConfig] =
    Json.format[AlertConfig]
}

case class Handler(
  command: String
)

object Handler {
  implicit val formats: OFormat[Handler] =
    Json.format[Handler]
}

case class SensuConfig(
  alertConfigs: Seq[AlertConfig] = Seq.empty,
  productionHandler: Map[String, Handler] = Map.empty
)

object AlertConfigSchedulerService {

  class NonClosableInputStream(inputStream: ZipInputStream) extends FilterInputStream(inputStream) {
    override def close(): Unit = {
      inputStream.closeEntry()
    }
  }

  def processZip(inputStream: InputStream): SensuConfig = {

    implicit val alertsReads: OFormat[AlertConfig] = AlertConfig.formats
    implicit val handlerReads: OFormat[Handler] = Handler.formats

    val zip = new ZipInputStream(inputStream)

    Iterator.continually(zip.getNextEntry)
      .takeWhile(z => z != null)
      .foldLeft(SensuConfig())((config, entry) => {
        entry.getName match {
          case p if p.startsWith("target/output/configs/") && p.endsWith(".json")   => {
              val json = Json.parse(new NonClosableInputStream(zip))
              val newConfig = config.copy(alertConfigs = config.alertConfigs :+ json.as[AlertConfig])
              newConfig
          }
          case p if p.startsWith("target/output/handlers/aws_production") && p.endsWith(".json")  => {
            config.copy(productionHandler = (Json.parse(new NonClosableInputStream(zip)) \ "handlers").as[Map[String, Handler]])
          }
          case _ => config
        }
    })
  }

  def processSensuConfig(sensuConfig: SensuConfig): Seq[AlertEnvironmentHandler] = {
    sensuConfig.alertConfigs.map(alertConfig => {
      AlertEnvironmentHandler(serviceName = trimServiceName(alertConfig.app),
        production = alertConfig
          .handlers
          .exists(h => hasProductionHandler(sensuConfig.productionHandler, h)))
    })

  }

  def hasProductionHandler(productionHandlers: Map[String, Handler], handler: String): Boolean = {
    productionHandlers.get(handler).exists(p => !p.command.contains("noop.rb"))
  }

  def trimServiceName(service: String): String = {
    service.split('.').head
  }
}


