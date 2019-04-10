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

import cats.data.EitherT
import cats.instances.all._
import cats.syntax.all._
import javax.inject.{Inject, Singleton}
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.NginxConfigFile
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRouteParser, NginxConfigIndexer}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.serviceconfigs.persistence.{FrontendRouteRepo, MongoLock}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try


case class SearchRequest(path:String)
case class SearchResults(env: String, path: String, url: String)

@Singleton
class NginxService @Inject()(frontendRouteRepo: FrontendRouteRepo,
                             parser: FrontendRouteParser,
                             nginxConnector: NginxConfigConnector,
                             mongoLock: MongoLock) {

  def findByService(repoName: String): Future[Seq[MongoFrontendRoute]] = frontendRouteRepo.findByService(repoName)

  def update(environments: Seq[String]): Future[Unit] =
    (for {
      _             <- EitherT.pure[Future, String](Logger.info("Refreshing frontend route data..."))
      configs       <- EitherT.liftF(
                         environments.toList.traverse(env => nginxConnector.configFor(env)).map(_.flatten)
                       )
      _             =  Logger.info(s"Downloaded ${configs.length} frontend route config files.")
      parsedConfigs <- EitherT.fromEither[Future](
                         configs.traverse[Either[String, ?], List[MongoFrontendRoute]](c =>
                           NginxService.parseConfig(parser, c).map(_.toList)
                         )
                       )
      _             =  Logger.info("Starting updated...")
      _             <- EitherT.liftF[Future, String, Option[Unit]](mongoLock.tryLock {
                          for {
                            configs <- Future.successful(parsedConfigs.flatten)
                            _       <- Future(Logger.info(s"About to update ${configs.length}"))
                            _       <- frontendRouteRepo.clearAll()
                            _       <- configs.traverse(frontendRouteRepo.update)
                          } yield ()
                       })
      _             =  Logger.info("Update complete")
     } yield ()
    )
    .leftMap(msg => Logger.error(s"Failed to update nginx configs: $msg"))
    .merge
}


object NginxService {

  def urlToService(url: String) : String = Try(new URL(url).getHost)
    .map(url => url.split("\\.").headOption.getOrElse(url))
    .getOrElse(url)

  def parseConfig(parser: FrontendRouteParser, configFile: NginxConfigFile): Either[String, Seq[MongoFrontendRoute]] = {

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
          isRegex              = r.isRegex))
      )
  }

}
