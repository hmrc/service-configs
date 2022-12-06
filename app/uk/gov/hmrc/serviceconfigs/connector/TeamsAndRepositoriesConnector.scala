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

package uk.gov.hmrc.serviceconfigs.connector

import javax.inject.{Inject, Singleton}
import play.api.Logging

import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.{ExecutionContext, Future}

object TeamsAndRepositoriesConnector {
  case class Repo(name: String)

  import play.api.libs.json.Reads._
  import play.api.libs.json._

  val readsRepo: Reads[Repo] =
    (__ \ "name").read[String].map(Repo)
}

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  serviceConfigs: ServicesConfig,
  httpClientV2  : HttpClientV2
)(implicit ec: ExecutionContext) extends Logging {
  import uk.gov.hmrc.http.HttpReads.Implicits._

  private val teamsAndServicesUrl =
    serviceConfigs.baseUrl("teams-and-repositories")

  implicit private val hc = HeaderCarrier()
  implicit private val rd = TeamsAndRepositoriesConnector.readsRepo

  def getRepos(archived: Option[Boolean] = None, repoType: Option[String] = None): Future[Seq[TeamsAndRepositoriesConnector.Repo]] =
    httpClientV2
      .get(url"$teamsAndServicesUrl/api/v2/repositories?archived=$archived&repoType=$repoType")
      .execute[List[TeamsAndRepositoriesConnector.Repo]]
}
