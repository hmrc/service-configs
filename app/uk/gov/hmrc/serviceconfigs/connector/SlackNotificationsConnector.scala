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

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._
import play.api.libs.ws.JsonBodyWritables.writeableOf_JsValue
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, RepoName, ServiceName, TeamName}

import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class SlackNotificationsConnector @Inject()(
  httpClientV2  : HttpClientV2,
  configuration : Configuration,
  servicesConfig: ServicesConfig,
)(using
  ec: ExecutionContext
) extends Logging{

  import HttpReads.Implicits._

  private val serviceUrl: String = servicesConfig.baseUrl("slack-notifications")

  private val internalAuthToken = configuration.get[String]("internal-auth.token")

  def sendMessage(message: SlackNotificationRequest)(using hc: HeaderCarrier): Future[SlackNotificationResponse] = {
    given Writes[SlackNotificationRequest] = SlackNotificationRequest.writes
    given Reads[SlackNotificationResponse] = SlackNotificationResponse.reads

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
}

final case class SlackNotificationError(
  code   : String,
  message: String
)

final case class SlackNotificationResponse(
  errors: List[SlackNotificationError]
)

object SlackNotificationResponse {
  val reads: Reads[SlackNotificationResponse] = {
    given sneReads: Reads[SlackNotificationError] =
      ( (__ \ "code"   ).read[String]
      ~ (__ \ "message").read[String]
      )(SlackNotificationError.apply _)

    (__ \ "errors")
      .readWithDefault[List[SlackNotificationError]](List.empty)
      .map(SlackNotificationResponse.apply)
  }
}

final case class GithubTeam(
  teamName: String,
  by: String = "github-team"
 )
object GithubTeam {
  val writes: Writes[GithubTeam] = Json.writes[GithubTeam]
}

final case class SlackNotificationRequest(
  channelLookup: GithubTeam,
  displayName  : String,
  emoji        : String,
  text         : String,
  blocks       : Seq[JsObject]
)

object SlackNotificationRequest {
  val writes: Writes[SlackNotificationRequest] = {
    given Writes[GithubTeam] = GithubTeam.writes
    Json.writes[SlackNotificationRequest]
  }

  def downstreamMarkedAsDeprecated(channelLookup: GithubTeam, eolRepository: RepoName, eol: Option[Instant], impactedRepositories: Seq[RepoName]): SlackNotificationRequest = {

    val repositoryHref: String = s"<https://catalogue.tax.service.gov.uk/repositories/${eolRepository.asString}|${eolRepository.asString}>"

    val deprecatedText: String = eol match {
      case Some(date) =>
        val utc = ZoneId.of("UTC")
        val eolFormatted = date.atZone(utc).toLocalDate.format(DateTimeFormatter.ofPattern("dd MMM uuuu"))
        s"$repositoryHref is marked as deprecated with an end of life date of `$eolFormatted`."
      case _          => s"$repositoryHref is marked as deprecated."
    }

    val repositoryElements: Seq[JsObject] = impactedRepositories.map {
      repoName =>
        Json.parse(
          s"""
           |{
           |	"type": "rich_text_section",
           |	"elements": [
           |    {
           |	    "type": "link",
           |	    "url": "https://catalogue.tax.service.gov.uk/repositories/${repoName.asString}",
           |	    "text": "${repoName.asString}"
           |	  }
           |	]
           |}
           |""".stripMargin
        ).as[JsObject]
    }

    val block1: JsObject = Json.parse(
      s"""
         |{
         |  "type": "section",
         |  "text": {
         |    "type": "mrkdwn",
         |    "text": "Hello ${channelLookup.teamName}, \\n$deprecatedText"
         |  }
         |}
         |""".stripMargin
    ).as[JsObject]

    val block2 = Json.parse(
      s"""
         |{
         |    "type": "rich_text",
         |    "elements": [
         |        {
         |            "type": "rich_text_section",
         |            "elements": [
         |                {
         |                    "type": "text",
         |                    "text": "The following services may have a dependency on this repository:"
         |                }
         |            ]
         |        },
         |        {
         |            "type": "rich_text_list",
         |            "style": "bullet",
         |            "elements": ${Json.toJson(repositoryElements)}
         |        }
         |    ]
         |}
         |""".stripMargin
    ).as[JsObject]


    SlackNotificationRequest(
      channelLookup = channelLookup,
      displayName   = "MDTP Catalogue",
      emoji         = ":tudor-crown:",
      text          = s"A downstream service has been marked as deprecated",
      blocks        = Seq(block1, block2)
    )
  }


  def bobbyWarning(channelLookup: GithubTeam, teamName: TeamName, warnings: List[(ServiceName, BobbyRule)]): SlackNotificationRequest = {
    val msg: JsObject = Json.parse(
      s"""
         |{
         |  "type": "section",
         |  "text": {
         |    "type": "mrkdwn",
         |    "text": "Hello ${teamName.asString}, please be aware that the following builds will fail soon because of upcoming Bobby Rules:"
         |  }
         |}
         |""".stripMargin
    ).as[JsObject]

    val rules = warnings.map { case (serviceName, rule) =>
      Json.parse(
        s"""
           |{
           |  "type": "section",
           |  "text": {
           |    "type": "mrkdwn",
           |    "text": "`${serviceName.asString}` will fail from *${rule.from}* with dependency on ${rule.organisation}.${rule.name} ${rule.range} - see <https://catalogue.tax.service.gov.uk/repositories/${serviceName.asString}#environmentTabs|Catalogue>"
           |  }
           |}
           |""".stripMargin
      ).as[JsObject]
    }

    SlackNotificationRequest(
      channelLookup = channelLookup,
      displayName   = "BobbyWarnings",
      emoji         = ":platops-bobby:",
      text          = "There are upcoming Bobby Rules affecting your service(s)",
      blocks        = msg :: rules
    )
  }
}

