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
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.{AdminFrontendRoute, Environment, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class RoutesConfigConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support:

  private val token = "TOKEN"

  private val githubConfig = GithubConfig(Configuration(
    "github.open.api.apiurl" -> s"$wireMockUrl/api",
    "github.open.api.rawurl" -> s"$wireMockUrl/raw",
    "github.open.api.token"  -> token
  ))

  private val connector = RoutesConfigConnector(httpClientV2, githubConfig)

  "RoutesConfigConnector.allAdminFrontendRoutes" should:
    "return admin frontend routes" in:
      stubFor:
        get(urlEqualTo(s"/raw/hmrc/admin-frontend-proxy/HEAD/config/routes.yaml"))
          .willReturn:
            aResponse()
              .withBody:
                """|
                   |/route1:
                   | microservice: serviceName
                   | allow:
                   |  development:
                   |    - mdtp-vpn
                   |    - stride
                   |  qa:
                   |    - mdtp-vpn
                   |/upstream:
                   |  allow:
                   |    local:
                   |      - all
                   |  microservice: test-upstream
                   |""".stripMargin

      connector.allAdminFrontendRoutes().futureValue shouldBe Seq(
        AdminFrontendRoute(
          ServiceName("serviceName"),
          "/route1",
          Map(Environment.Development -> List("mdtp-vpn", "stride"), Environment.QA -> List("mdtp-vpn")),
          "https://github.com/hmrc/admin-frontend-proxy/blob/HEAD/config/routes.yaml#L2"
        ),
        AdminFrontendRoute(
          ServiceName("test-upstream"),
          "/upstream",
          Map(),
          "https://github.com/hmrc/admin-frontend-proxy/blob/HEAD/config/routes.yaml#L10"
        )
      )

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/raw/hmrc/admin-frontend-proxy/HEAD/config/routes.yaml"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )
