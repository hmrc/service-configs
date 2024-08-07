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
import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.serviceconfigs.model.{ServiceName, TeamName}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceDependenciesConnector @Inject() (
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig)
(using
  ec : ExecutionContext,
) extends Logging:

  import HttpReads.Implicits._

  private given HeaderCarrier = HeaderCarrier()

  private val serviceUrl = servicesConfig.baseUrl("service-dependencies")

  def getAffectedServices(group: String, artefact: String, versionRange: String): Future[Seq[AffectedService]] =
    given Reads[AffectedService] = AffectedService.reads
    httpClientV2
      .get(url"$serviceUrl/api/repoDependencies?group=$group&artefact=$artefact&versionRange=$versionRange&repoType=Service")
      .execute[Seq[AffectedService]]

case class AffectedService(serviceName: ServiceName, teamNames: List[TeamName])

object AffectedService:
  val reads: Reads[AffectedService] =
    ( (__ \ "repoName").read[String      ].map(ServiceName.apply)
    ~ (__ \ "teams"   ).read[List[String]].map(_.map(TeamName.apply))
    )(AffectedService.apply _)
