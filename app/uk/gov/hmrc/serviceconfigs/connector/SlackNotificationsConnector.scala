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

import play.api.libs.json._
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.serviceconfigs.model.BobbyRule

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class SlackNotificationsConnector @Inject()(
  httpClientV2  : HttpClientV2,
  configuration : Configuration,
  servicesConfig: ServicesConfig,
)(implicit
  ec: ExecutionContext
) extends Logging{

  import HttpReads.Implicits._

  private val serviceUrl: String = servicesConfig.baseUrl("slack-notifications")

  private val internalAuthToken = configuration.get[String]("internal-auth.token")

  def sendMessage(message: SlackNotificationRequest)(implicit hc: HeaderCarrier): Future[SlackNotificationResponse] =
    httpClientV2
      .post(url"$serviceUrl/slack-notifications/v2/notification")
      .withBody(Json.toJson(message))
      .setHeader("Authorization" -> internalAuthToken)
      .execute[SlackNotificationResponse]
      .recoverWith {
        case NonFatal(ex) =>
          logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)
      }
}

final case class SlackNotificationError (
 code   : String,
 message: String
)

object SlackNotificationError {
  implicit val format: OFormat[SlackNotificationError] =
    Json.format[SlackNotificationError]
}

final case class SlackNotificationResponse (
  successfullySentTo: Seq[String]                  = Nil,
  errors            : List[SlackNotificationError] = Nil
 ) {
}

object SlackNotificationResponse {
  implicit val format: OFormat[SlackNotificationResponse] =
    Json.format[SlackNotificationResponse]
}

final case class GithubTeam(
  teamName: String,
  by: String = "github-team"
 )
object GithubTeam {
  implicit val writes: Writes[GithubTeam] = Json.writes[GithubTeam]
}

final case class SlackNotificationRequest(
  channelLookup: GithubTeam,
  displayName  : String,
  emoji        : String,
  text         : String,
  blocks       : Seq[JsObject]
)

object SlackNotificationRequest {
  implicit val writes: OWrites[SlackNotificationRequest] = Json.writes[SlackNotificationRequest]

  def bobbyWarning(channelLookup: GithubTeam, teamName: String, warnings: List[(Service, BobbyRule)]): SlackNotificationRequest = {
    val msg: JsObject = Json.parse(
      s"""
         |{
         |  "type": "section",
         |  "text": {
         |    "type": "mrkdwn",
         |    "text": "Hello $teamName, please be aware that the following builds will fail soon because of new Bobby Rules:"
         |  }
         |}
         |""".stripMargin
    ).as[JsObject]

    val rules = warnings.map { case (service, rule) =>
      Json.parse(
        s"""
           |{
           |  "type": "section",
           |  "text": {
           |    "type": "mrkdwn",
           |    "text": "`${service.serviceName}` will fail from *${rule.from}* with dependency on ${rule.organisation}.${rule.name} ${rule.range} - see <https://catalogue.tax.service.gov.uk/repositories/${service.serviceName}#environmentTabs|Catalogue>"
           |  }
           |}
           |""".stripMargin
      ).as[JsObject]
    }

    SlackNotificationRequest(
      channelLookup,
      "BobbyWarnings",
      ":platops-bobby:",
      "There are upcoming Bobby Rules affecting your service(s)",
      msg :: rules
    )
  }
}

