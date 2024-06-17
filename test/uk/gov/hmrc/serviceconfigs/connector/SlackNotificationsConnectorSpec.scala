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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.libs.json.JsObject
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global

class SlackNotificationsConnectorSpec
  extends AnyWordSpec
  with Matchers
  with ScalaFutures
  with IntegrationPatience
  with WireMockSupport
  with HttpClientV2Support:

    "Connector" should:
      "use internal auth" in:
        val expectedResponse = SlackNotificationResponse(errors = Nil)

        stubFor(
          post(urlEqualTo("/slack-notifications/v2/notification"))
            .willReturn(
              aResponse()
                .withStatus(200)
                .withBody(
                  """
               {
                 "successfullySentTo": [],
                 "errors": []
               }"""
                )
            )
        )

        val configuration =
          Configuration(
            "microservice.services.slack-notifications.host" -> wireMockHost,
            "microservice.services.slack-notifications.port" -> wireMockPort,
            "internal-auth.token"                            -> "token"
          )

        val connector = SlackNotificationsConnector(
          httpClientV2,
          configuration,
          ServicesConfig(configuration)
        )

        val slackMessage =
          SlackNotificationRequest(
            channelLookup = GithubTeam(teamName = "PlatOps"),
            displayName   = "BobbyWarnings",
            emoji         = ":platops-bobby:",
            text          = "text",
            blocks        = Seq.empty[JsObject]
          )


        val response = connector.sendMessage(slackMessage)(using HeaderCarrier(authorization = None)).futureValue

        response shouldBe expectedResponse

        verify(
          postRequestedFor(urlEqualTo("/slack-notifications/v2/notification"))
            .withRequestBody(equalToJson(
              """{
             "channelLookup": {
               "teamName": "PlatOps",
               "by"      : "github-team"
             },
             "displayName": "BobbyWarnings",
             "emoji": ":platops-bobby:",
             "text": "text",
             "blocks": []
           }"""
            ))
            .withHeader("Authorization", equalTo("token"))
        )
