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

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRoute, FrontendRoutersParser}
import uk.gov.hmrc.serviceconfigs.persistence.FrontendRouteRepo
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


case class SearchRequest(path:String)
case class SearchResults(env: String, path: String, url: String)

@Singleton
class NginxService @Inject()(frontendRouteRepo: FrontendRouteRepo,
                             parser: FrontendRoutersParser,
                             nginxConnector: NginxConfigConnector) {



  val environments = Seq("production", "qa", "staging", "development")

  def search(request: SearchRequest) : Future[Seq[SearchResults]] = ???

  def findByService(repoName: String): Future[Seq[MongoFrontendRoute]] = frontendRouteRepo.findByService(repoName)

  def retrieveConfig(env: String): Future[Seq[FrontendRoute]] = {
    // download from github
    nginxConnector.configFor(env).map( _.map(parser.parseConfig).getOrElse(Seq.empty) )
  }

  def retrieveConfigs(envs: Seq[String] = environments): Future[Seq[Seq[FrontendRoute]]] = {
    Future.sequence(envs.map(retrieveConfig))
  }

}
