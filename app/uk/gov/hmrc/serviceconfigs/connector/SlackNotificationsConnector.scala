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

import java.util.Base64.getEncoder
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

  private val authorizationHeaderValue = {
    val username = configuration.get[String]("microservice.services.slack-notifications.basicAuth.username")
    val password = configuration.get[String]("microservice.services.slack-notifications.basicAuth.password")

    s"Basic ${getEncoder.encode(s"$username:$password".getBytes("UTF-8"))}"
  }

  def sendMessage(message: SlackNotificationRequest)(implicit hc: HeaderCarrier): Future[SlackNotificationResponse] =
    httpClientV2
      .post(url"$serviceUrl/slack-notifications/notification")
      .withBody(Json.toJson(message))
      .setHeader("Authorization" -> authorizationHeaderValue)
      .execute[SlackNotificationResponse]
      .recoverWith {
        case NonFatal(ex) =>
          logger.error(s"Unable to notify ${message.channelLookup} on Slack", ex)
          Future.failed(ex)
      }
}

final case class SlackNotificationError(
                                         code   : String,
                                         message: String
                                       )

object SlackNotificationError {
  implicit val format: OFormat[SlackNotificationError] =
    Json.format[SlackNotificationError]
}

final case class SlackNotificationResponse(
                                            successfullySentTo: Seq[String]                  = Nil,
                                            errors            : List[SlackNotificationError] = Nil
                                          ) {
  def hasSentMessages: Boolean = successfullySentTo.nonEmpty
}

object SlackNotificationResponse {

  implicit val format: OFormat[SlackNotificationResponse] =
    Json.format[SlackNotificationResponse]
}

sealed trait ChannelLookup {
  def by: String
}

object ChannelLookup {

  final case class GithubTeam(
                              repositoryName: String,
                              by            : String = "github-team"
                              ) extends ChannelLookup


  implicit val writes: Writes[ChannelLookup] = Writes {
    case s: GithubTeam => Json.toJson(s)(Json.writes[GithubTeam])
  }
}

final case class Attachment(text: String, fields: Seq[Attachment.Field] = Nil)

object Attachment {
  final case class Field(
                          title: String,
                          value: String,
                          short: Boolean
                        )

  object Field {
    implicit val format: OFormat[Field] = Json.format[Field]
  }

  implicit val format: OFormat[Attachment] = Json.format[Attachment]

}

final case class MessageDetails(
                                 text                : String,
                                 username            : String,
                                 iconEmoji           : String,
                                 attachments         : Seq[Attachment],
                                 showAttachmentAuthor: Boolean
                               )

object MessageDetails {
  implicit val writes: OWrites[MessageDetails] = Json.writes[MessageDetails]
}

final case class SlackNotificationRequest(channelLookup : ChannelLookup, messageDetails: MessageDetails)

object SlackNotificationRequest {
  implicit val writes: OWrites[SlackNotificationRequest] = Json.writes[SlackNotificationRequest]
}

