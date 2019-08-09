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
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.NginxConfigFile
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRouteParser, NginxConfigIndexer}
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}
import uk.gov.hmrc.serviceconfigs.persistence.{FrontendRouteRepo, MongoLock}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try


case class SearchRequest(path:String)
case class SearchResults(env: String, path: String, url: String)

@Singleton
class NginxService @Inject()(
  frontendRouteRepo: FrontendRouteRepo,
  parser: FrontendRouteParser,
  nginxConnector: NginxConfigConnector,
  mongoLock: MongoLock) {

  def update(environments: List[String]): Future[Unit] =
    Future.sequence(
      environments.map(updateNginxRoutesFor(_))
    ).map(_ => ())

  private def updateNginxRoutesFor(environment: String): Future[Unit] = {
    Logger.info(s"Refreshing frontend route data for $environment...")
    nginxConnector.configFor(environment)
      .flatMap {
        case None => Future.successful(Logger.error(s"Unable to retrieve routes file"))
        case Some(file) =>
          NginxService.parseConfig(parser, file)
            .fold(
              msg => Future.successful(Logger.error(s"Failed to update nginx configs: $msg")),
              routes => insertNginxRoutesInMongo(routes))
            .map(_ => Logger.info(s"Update complete for $environment"))
      }
  }

  private def insertNginxRoutesInMongo(parsedConfigs: List[MongoFrontendRoute]): Future[Unit] =
    mongoLock.tryLock {
      Logger.info(s"Inserting ${parsedConfigs.length} routes into mongo")
      for {
        _ <- frontendRouteRepo.clearAll()
        _ <- parsedConfigs.traverse(frontendRouteRepo.update)
      } yield ()
    }.map(_ => ())
}


object NginxService {

  def urlToService(url: String) : String = Try(new URL(url).getHost)
    .map(url => url.split("\\.").headOption.getOrElse(url))
    .getOrElse(url)

  def parseConfig(parser: FrontendRouteParser, configFile: NginxConfigFile): Either[String, List[MongoFrontendRoute]] = {

    Logger.info(s"Parsing ${configFile.environment} frontend config from ${configFile.url}")

    val indexes: Map[String, Int] = NginxConfigIndexer.index(configFile.content)
    parser.parseConfig(configFile.content)
      .map(
        _.map(r => MongoFrontendRoute(
          service              = NginxService.urlToService(r.backendPath),
          frontendPath         = r.frontendPath,
          backendPath          = r.backendPath,
          environment          = configFile.environment,
          ruleConfigurationUrl = NginxConfigIndexer.generateUrl(configFile.environment, r.frontendPath, indexes).getOrElse(""),
          shutterKillswitch = r.shutterKillswitch.map(ks => MongoShutterSwitch(ks.switchFile, ks.statusCode)),
          shutterServiceSwitch = r.shutterServiceSwitch.map(s => MongoShutterSwitch(s.switchFile, s.statusCode, s.errorPage, s.rewriteRule)),
          isRegex              = r.isRegex))
      )
  }

}
