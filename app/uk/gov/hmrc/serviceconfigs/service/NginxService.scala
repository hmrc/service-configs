/*
 * Copyright 2019 HM Revenue & Customs
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

import java.net.URL

import cats.instances.all._
import cats.syntax.all._
import javax.inject.{Inject, Singleton}
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.NginxConfigFile
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRouteParser, NginxConfigIndexer}
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}
import uk.gov.hmrc.serviceconfigs.persistence.{FrontendRouteRepository, MongoLock}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class SearchRequest(path: String)
case class SearchResults(env: String, path: String, url: String)

@Singleton
class NginxService @Inject()(
  frontendRouteRepo: FrontendRouteRepository,
  parser           : FrontendRouteParser,
  nginxConnector   : NginxConfigConnector,
  nginxConfig      : NginxConfig,
  mongoLock        : MongoLock
  )(implicit ec: ExecutionContext) {

  def update(environments: List[String]): Future[Unit] =
    for {
      _              <- Future.successful(Logger.info(s"Update started..."))
      frontendRoutes <- environments.traverse(updateNginxRoutesForEnv).map(_.flatten)
      _              <- insertNginxRoutesIntoMongo(frontendRoutes)
      _              =  Logger.info(s"Update complete...")
    } yield ()

  private def updateNginxRoutesForEnv(environment: String): Future[List[MongoFrontendRoute]] =
    for {
      _           <- Future.successful(Logger.info(s"Refreshing frontend route data for $environment..."))
      routesFiles <- nginxConfig.frontendConfigFileNames
                      .traverse(nginxConnector.getNginxRoutesFile(_, environment))
                      .map(_.flatten)
    } yield routesFiles.flatMap(processNginxRouteFile)

  private def processNginxRouteFile(nginxConfigFile: NginxConfigFile): List[MongoFrontendRoute] =
    NginxService.parseConfig(parser, nginxConfigFile).getOrElse {
      Logger.error(s"Failed to update nginx configs: ${nginxConfigFile.url}")
      List.empty
    }

  private def insertNginxRoutesIntoMongo(parsedConfigs: List[MongoFrontendRoute]): Future[Unit] =
    mongoLock
      .attemptLockWithRelease {
        for {
          _ <- frontendRouteRepo.clearAll()
          _ =  Logger.info(s"Inserting ${parsedConfigs.length} routes into mongo")
          _ <- parsedConfigs.traverse(frontendRouteRepo.update)
        } yield ()
      }
      .map(_ => ())
}

object NginxService {

  def urlToService(url: String): String =
    Try(new URL(url).getHost)
      .map(url => url.split("\\.").headOption.getOrElse(url))
      .getOrElse(url)

  def parseConfig(
    parser: FrontendRouteParser,
    configFile: NginxConfigFile): Either[String, List[MongoFrontendRoute]] = {

    Logger.info(s"Parsing ${configFile.environment} frontend config from ${configFile.url}")

    val indexes: Map[String, Int] =
      NginxConfigIndexer.index(configFile.content)

    parser
      .parseConfig(configFile.content)
      .map(
        _.map(
          r =>
            MongoFrontendRoute(
              service              = NginxService.urlToService(r.backendPath),
              frontendPath         = r.frontendPath,
              backendPath          = r.backendPath,
              environment          = configFile.environment,
              routesFile           = configFile.fileName,
              ruleConfigurationUrl = NginxConfigIndexer
                                       .generateUrl(configFile.fileName, configFile.branch, configFile.environment, r.frontendPath, indexes)
                                       .getOrElse(""),
              markerComments       = r.markerComments,
              shutterKillswitch    = r.shutterKillswitch.map(ks => MongoShutterSwitch(ks.switchFile, ks.statusCode)),
              shutterServiceSwitch = r.shutterServiceSwitch.map(s =>
                MongoShutterSwitch(s.switchFile, s.statusCode, s.errorPage, s.rewriteRule)),
              isRegex              = r.isRegex
          ))
      )
  }
}
