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

package uk.gov.hmrc.serviceconfigs.connector

import play.api.Logging
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.Reads
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.serviceconfigs.model.{DigitalService, RepoName, ServiceType, Tag, TeamName}

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


object TeamsAndRepositoriesConnector:

  case class Repo(repoName: RepoName, teamNames: Seq[String], endOfLifeDate: Option[Instant], isDeprecated: Boolean = false)
  case class DecommissionedRepo(repoName: RepoName)

  import play.api.libs.json.Reads._
  import play.api.libs.json._

  val readsRepo: Reads[Repo] =
    ( (__ \ "name"         ).read[String].map(RepoName.apply)
    ~ (__ \ "teamNames"    ).read[Seq[String]]
    ~ (__ \ "endOfLifeDate").readNullable[Instant]
    ~ (__ \ "isDeprecated" ).readWithDefault[Boolean](false)
    )(Repo.apply _)

  val readsDeletedRepo: Reads[Repo] =
    ( (__ \ "name"     ).read[String].map(RepoName.apply)
    ~ (__ \ "teamNames").readWithDefault[Seq[String]](Nil)
    ~ Reads.pure(None  )
    ~ Reads.pure(true  )
    )(Repo.apply _     )

  val readsDecommissionedRepo: Reads[DecommissionedRepo] =
    (__ \ "repoName")
      .read[String]
      .map(RepoName.apply)
      .map(DecommissionedRepo.apply)

@Singleton
class TeamsAndRepositoriesConnector @Inject()(
  serviceConfigs: ServicesConfig,
  httpClientV2  : HttpClientV2
)(using
  ec: ExecutionContext
) extends Logging:
  import uk.gov.hmrc.http.HttpReads.Implicits._

  private val teamsAndServicesUrl =
    serviceConfigs.baseUrl("teams-and-repositories")

  private given HeaderCarrier = HeaderCarrier()

  def getRepos(
    archived      : Option[Boolean]        = None
  , repoType      : Option[String]         = None
  , teamName      : Option[TeamName]       = None
  , digitalService: Option[DigitalService] = None
  , serviceType   : Option[ServiceType]    = None
  , tags          : Seq[Tag]               = Nil
  ): Future[Seq[TeamsAndRepositoriesConnector.Repo]] =
    given Reads[TeamsAndRepositoriesConnector.Repo] = TeamsAndRepositoriesConnector.readsRepo
    httpClientV2
      .get(url"$teamsAndServicesUrl/api/v2/repositories?team=${teamName.map(_.asString)}&digitalServiceName=${digitalService.map(_.asString)}&serviceType=${serviceType.map(_.asString)}&tag=${tags.map(_.asString)}&repoType=${repoType}")
      .execute[Seq[TeamsAndRepositoriesConnector.Repo]]

  def getDeletedRepos(
    repoType   : Option[String]       = None
  ): Future[Seq[TeamsAndRepositoriesConnector.Repo]] =
    given Reads[TeamsAndRepositoriesConnector.Repo] = TeamsAndRepositoriesConnector.readsDeletedRepo
    httpClientV2
      .get(url"$teamsAndServicesUrl/api/deleted-repositories?repoType=$repoType")
      .execute[Seq[TeamsAndRepositoriesConnector.Repo]]

  // returns deleted or archived - which are stored in two different mongo collections
  def getDecommissionedServices(): Future[Seq[TeamsAndRepositoriesConnector.DecommissionedRepo]] =
    given Reads[TeamsAndRepositoriesConnector.DecommissionedRepo] = TeamsAndRepositoriesConnector.readsDecommissionedRepo
    httpClientV2
      .get(url"$teamsAndServicesUrl/api/v2/decommissioned-repositories?repoType=service")
      .execute[Seq[TeamsAndRepositoriesConnector.DecommissionedRepo]]
