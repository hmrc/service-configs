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

import java.net.URL
import cats.instances.all._
import cats.syntax.all._

import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{Environment, NginxConfigFile}
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRouteParser, NginxConfigIndexer, YamlConfigParser}
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}
import uk.gov.hmrc.serviceconfigs.persistence.FrontendRouteRepository

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class SearchRequest(path: String)
case class SearchResults(env: String, path: String, url: String)

@Singleton
class NginxService @Inject()(
  frontendRouteRepo: FrontendRouteRepository,
  parser           : FrontendRouteParser,
  yamlParser       : YamlConfigParser,
  nginxConnector   : NginxConfigConnector,
  nginxConfig      : NginxConfig
)(implicit ec: ExecutionContext
) extends Logging {

  def update(environments: List[Environment]): Future[Unit] =
    for {
      _          <- Future.successful(logger.info(s"Update started..."))
      yamlConfig <- nginxConnector.getYamlRoutesFile().map(yamlParser.parseConfig)
      _          <- environments.traverse { environment =>
                      val yamlRoutes = yamlConfig.filter(_.environment == environment)
                      updateNginxRoutesForEnv(environment, yamlRoutes)
                        .recover { case e => logger.error(s"Failed to update routes for $environment: ${e.getMessage}", e); Nil }
                    }
      _          =  logger.info(s"Update complete...")
    } yield ()

  private def updateNginxRoutesForEnv(environment: Environment, yamlRoutes: Set[MongoFrontendRoute]): Future[List[MongoFrontendRoute]] =
    for {
      _        <- Future.successful(logger.info(s"Refreshing frontend route data for $environment..."))
      routes   <- nginxConfig.frontendConfigFileNames
                   .traverse { configFile =>
                     nginxConnector.getNginxRoutesFile(configFile, environment)
                      .map(processNginxRouteFile)
                   }.map(_.flatten)
      _        <- frontendRouteRepo.replaceEnv(environment, routes.toSet ++ yamlRoutes)
    } yield routes

  private def processNginxRouteFile(nginxConfigFile: NginxConfigFile): List[MongoFrontendRoute] =
    NginxService.parseConfig(parser, nginxConfigFile)
      .fold(error => sys.error(s"Failed to parse nginx configs: ${nginxConfigFile.url}: $error"), identity)
}

object NginxService extends Logging {

  def urlToService(url: String): String =
    Try(new URL(url).getHost)
      .map(url => url.split("\\.").headOption.getOrElse(url))
      .getOrElse(url)

  def parseConfig(
    parser    : FrontendRouteParser,
    configFile: NginxConfigFile
  ): Either[String, List[MongoFrontendRoute]] = {

    logger.info(s"Parsing ${configFile.environment} frontend config from ${configFile.url}")

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
