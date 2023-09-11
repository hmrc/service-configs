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
import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import play.api.libs.functional.syntax.toFunctionalBuilderOps

/*
  Copied from the Catalogue Frontend.
 */
@Singleton
class UserManagementConnector @Inject()(
  httpClientV2  : HttpClientV2
, servicesConfig: ServicesConfig
)(implicit
  ec: ExecutionContext
) {

  private val baseUrl = servicesConfig.baseUrl("user-management")

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  def getSlackChannelByTeam(team: String): Future[String] = {
    val url: URL = url"$baseUrl/user-management/teams/$team"

    implicit val ltR: Reads[LdapTeam] = LdapTeam.reads

    httpClientV2
      .get(url)
      .execute[Option[LdapTeam]]
      .map {
        _.map {
          case LdapTeam(Some(slack), Some(slackNotification)) => slack.name
          case LdapTeam(None, Some(slackNotification)) => slackNotification.name
        }.getOrElse("DefaultSlackChannel") //todo make this a configurable value
      }
  }

}

final case class LdapTeam(
                             slack            : Option[SlackInfo]
                           , slackNotification: Option[SlackInfo]
                         )

object LdapTeam {
  val reads: Reads[LdapTeam] = {
    implicit val siR: Reads[SlackInfo] = SlackInfo.reads
    ( (__ \ "slack"            ).readNullable[SlackInfo]
      ~ (__ \ "slackNotification").readNullable[SlackInfo]
      )(LdapTeam.apply _)
  }
}

final case class SlackInfo(url: String) {
  val name: String = url.split("/").lastOption.getOrElse(url)
}

object SlackInfo {
  val reads: Reads[SlackInfo] = Reads.StringReads.map(SlackInfo.apply)
}
