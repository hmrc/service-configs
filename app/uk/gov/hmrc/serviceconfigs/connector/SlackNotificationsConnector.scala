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
  servicesConfig: ServicesConfig
)(using
  ec: ExecutionContext
) extends Logging:

  import HttpReads.Implicits._

  private val serviceUrl: String = servicesConfig.baseUrl("slack-notifications")

  private val internalAuthToken = configuration.get[String]("internal-auth.token")

  def sendMessage(message: SlackNotificationRequest)(using hc: HeaderCarrier): Future[SlackNotificationResponse] =
    given Writes[SlackNotificationRequest] = SlackNotificationRequest.writes
    given Reads[SlackNotificationResponse] = SlackNotificationResponse.reads

    httpClientV2
      .post(url"$serviceUrl/slack-notifications/v2/notification")
      .withBody(Json.toJson(message))
      .setHeader("Authorization" -> internalAuthToken)
      .execute[SlackNotificationResponse]
      .recoverWith:
        case NonFatal(ex) =>
          logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)

final case class SlackNotificationError(
  code   : String,
  message: String
)

final case class SlackNotificationResponse(
  errors: List[SlackNotificationError]
)

object SlackNotificationResponse:
  val reads: Reads[SlackNotificationResponse] =
    given sneReads: Reads[SlackNotificationError] =
      ( (__ \ "code"   ).read[String]
      ~ (__ \ "message").read[String]
      )(SlackNotificationError.apply _)

    (__ \ "errors")
      .readWithDefault[List[SlackNotificationError]](List.empty)
      .map(SlackNotificationResponse.apply)

enum ChannelLookup(val by: String):
    case GithubTeam   (teamName      : String     ) extends ChannelLookup("github-team")
    case SlackChannels(slackChannels : Seq[String]) extends ChannelLookup("slack-channel")

final case class SlackNotificationRequest(
  channelLookup  : ChannelLookup,
  displayName    : String,
  emoji          : String,
  text           : String,
  blocks         : Seq[JsObject],
  callbackChannel: Option[String] = None
)

object SlackNotificationRequest:
  val writes: Writes[SlackNotificationRequest] =
    given OWrites[ChannelLookup.GithubTeam   ] = Writes.at[String     ](__ \ "teamName"      ).contramap[ChannelLookup.GithubTeam   ](_.teamName)
    given OWrites[ChannelLookup.SlackChannels] = Writes.at[Seq[String]](__ \ "slackChannels" ).contramap[ChannelLookup.SlackChannels](_.slackChannels)
    given Writes[ChannelLookup] =
      Writes {
        case s: ChannelLookup.GithubTeam    => Json.obj("by" -> s.by).deepMerge(Json.toJsObject(s))
        case s: ChannelLookup.SlackChannels => Json.obj("by" -> s.by).deepMerge(Json.toJsObject(s))
      }
    
    ( (__ \ "channelLookup"  ).write[ChannelLookup]
    ~ (__ \ "displayName"    ).write[String]
    ~ (__ \ "emoji"          ).write[String]
    ~ (__ \ "text"           ).write[String]
    ~ (__ \ "blocks"         ).write[Seq[JsValue]]
    ~ (__ \ "callbackChannel").writeNullable[String]
    )(o => Tuple.fromProductTyped(o))

  def downstreamMarkedAsDeprecated(channelLookup: ChannelLookup, teamName: TeamName, eolRepository: RepoName, eol: Option[Instant], impactedRepositories: Seq[RepoName]): SlackNotificationRequest =
    val repositoryHref: String = s"<https://catalogue.tax.service.gov.uk/repositories/${eolRepository.asString}|${eolRepository.asString}>"
    val deprecatedText: String =
      eol match
        case Some(date) =>
          val utc = ZoneId.of("UTC")
          val eolFormatted = date.atZone(utc).toLocalDate.format(DateTimeFormatter.ofPattern("dd MMM uuuu"))
          s"$repositoryHref is marked as deprecated with an end of life date of `$eolFormatted`."
        case _          => s"$repositoryHref is marked as deprecated."

    val repositoryElements: Seq[JsObject] =
      impactedRepositories.map: repoName =>
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

    val block1: JsObject = Json.parse(
      s"""
         |{
         |  "type": "section",
         |  "text": {
         |    "type": "mrkdwn",
         |    "text": "Hello ${teamName.asString}, \\n$deprecatedText"
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
      channelLookup   = channelLookup,
      displayName     = "MDTP Catalogue",
      emoji           = ":tudor-crown:",
      text            = s"A downstream service has been marked as deprecated",
      blocks          = Seq(block1, block2),
      callbackChannel = Some("team-platops-alerts")
    )

  def bobbyWarning(channelLookup: ChannelLookup, teamName: TeamName, warnings: List[(ServiceName, BobbyRule)]): SlackNotificationRequest =
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

    def blockFromText(text: String): JsObject = Json.parse(
      s"""
         |{
         |  "type": "section",
         |  "text": {
         |    "type": "mrkdwn",
         |    "text": "$text"
         |  }
         |}
         |""".stripMargin
    ).as[JsObject]

    val ruleTexts = warnings.map:
      case (serviceName, rule) =>
        s"`${serviceName.asString}` will fail from *${rule.from}* with dependency on ${rule.organisation}.${rule.name} ${rule.range} - see <https://catalogue.tax.service.gov.uk/repositories/${serviceName.asString}#environmentTabs|Catalogue>"

    // Slack API limits: 50 blocks total, 3000 chars per text block
    // With ~300 chars per service -> rule msg, we can safely fit 7 rules per block
    // This means we can safely handle Teams with up to 343 (7 * 49 remaining blocks) bobby warnings
    val RULES_PER_BLOCK = 7

    val ruleBlocks = ruleTexts
      .grouped(RULES_PER_BLOCK)
      .map(_.mkString("\\n")) // literal \n so it's sent to slack
      .map(blockFromText)
      .toList

    SlackNotificationRequest(
      channelLookup   = channelLookup,
      displayName     = "BobbyWarnings",
      emoji           = ":platops-bobby:",
      text            = "There are upcoming Bobby Rules affecting your service(s)",
      blocks          = msg :: ruleBlocks,
      callbackChannel = Some("team-platops-alerts")
    )
