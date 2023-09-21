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

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
@Singleton
class ServiceDependenciesConnector @Inject() (
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig)
(implicit
  ec : ExecutionContext,
) extends Logging {

  import HttpReads.Implicits._

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val serviceUrl = servicesConfig.baseUrl("service-dependencies")

  def getAffectedServices(group: String, artefact: String, versionRange: String): Future[Seq[AffectedService]] =
    httpClientV2.get(url"$serviceUrl/api/serviceDeps?group=$group&artefact=$artefact&versionRange=${versionRange}&scope=compile")
      .execute[Seq[AffectedService]]

}

case class AffectedService(service: Service, teams: List[Team])

object AffectedService {

  implicit val reads: Reads[AffectedService] =
    ((__ \ "slugName" ).read[String].map(Service.apply)
      ~ (__ \ "teams").read[List[String]].map(_.map(Team.apply))
   )(AffectedService.apply _)
}

case class Team(teamName: String) extends AnyVal

case class Service(serviceName: String) extends AnyVal


