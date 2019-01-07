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

package uk.gov.hmrc.serviceconfigs

import java.net.URL

import javax.inject.{Inject, Singleton}
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{FrontendRoute, NginxConfigFile}
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRoutersParser, NginxConfigIndexer, ParserFrontendRoute}
import uk.gov.hmrc.serviceconfigs.persistence.{FrontendRouteRepo, MongoLock}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.util.Try


case class SearchRequest(path:String)
case class SearchResults(env: String, path: String, url: String)

@Singleton
class NginxService @Inject()(frontendRouteRepo: FrontendRouteRepo,
                             parser: FrontendRoutersParser,
                             nginxConnector: NginxConfigConnector,
                             mongoLock: MongoLock) {


  def search(request: SearchRequest) : Future[Seq[SearchResults]] = ???

  def findByService(repoName: String): Future[Seq[MongoFrontendRoute]] = frontendRouteRepo.findByService(repoName)

  def update(environments: Seq[String]) = {

    Logger.info("Refreshing frontend route data...")
    val configs: Seq[NginxConfigFile] = Await.result( Future.sequence(environments.map(env => nginxConnector.configFor(env))), Duration(10, "seconds")).flatten

    Logger.info(s"Downloaded ${configs.length} frontend route config files.")
    val parsedConfigs: Seq[MongoFrontendRoute] = NginxService.joinConfigs( configs.map(c => NginxService.parseConfig(parser, c)) )

    Logger.info("Starting updated...")
    mongoLock.tryLock {
      Future.sequence(parsedConfigs.map(frontendRouteRepo.update))
    }

    Logger.info("Update complete")
  }

}


object NginxService {

  def urlToService(url: String) : String = Try(new URL(url).getHost).getOrElse(url)

  def joinConfigs(configs: Seq[Seq[MongoFrontendRoute]]) : Seq[MongoFrontendRoute] =
    configs.foldLeft(Seq.empty[MongoFrontendRoute])((res, v) => res ++ v )

  def parseConfig(parser: FrontendRoutersParser, configFile: NginxConfigFile): Seq[MongoFrontendRoute] = {

    Logger.info(s"Parsing ${configFile.environment} frontend config from ${configFile.url}")

    val indexes: Map[String, Int] = NginxConfigIndexer.index(configFile.content)
    parser.parseConfig(configFile.content)
      .map(r => MongoFrontendRoute(
        service              = NginxService.urlToService(r.proxy),
        frontendPath         = r.path,
        backendPath          = r.proxy,
        environment          = configFile.environment,
        ruleConfigurationUrl = NginxConfigIndexer.generateUrl(configFile.environment, r.path, indexes).getOrElse("")))
  }

}
