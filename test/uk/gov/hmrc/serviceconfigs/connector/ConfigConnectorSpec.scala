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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.{Environment, SlugInfoFlag}

import scala.concurrent.ExecutionContext.Implicits.global

class ConfigConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val token = "TOKEN"

  private val githubConfig = new GithubConfig(Configuration(
    "github.open.api.apiurl" -> s"$wireMockUrl/api",
    "github.open.api.rawurl" -> s"$wireMockUrl/raw",
    "github.open.api.token"  -> token
  ))

  private val connector = new ConfigConnector(httpClientV2, githubConfig)

  "ConfigConnector.serviceCommonConfigYaml" should {
    "return config" in {
      stubFor(
        get(urlEqualTo(s"/raw/hmrc/app-config-common/HEAD/production-frontend-common.yaml"))
          .willReturn(aResponse().withBody("config body"))
      )

      connector.serviceCommonConfigYaml(SlugInfoFlag.ForEnvironment(Environment.Production), serviceType = "frontend").futureValue shouldBe Some(
        "config body"
      )

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/raw/hmrc/app-config-common/HEAD/production-frontend-common.yaml"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )
    }

    "handle api-microservice" in {
      stubFor(
        get(urlEqualTo(s"/raw/hmrc/app-config-common/HEAD/production-microservice-common.yaml"))
          .willReturn(aResponse().withBody("config body"))
      )

      connector.serviceCommonConfigYaml(SlugInfoFlag.ForEnvironment(Environment.Production), serviceType = "api-microservice").futureValue shouldBe Some(
        "config body"
      )

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/raw/hmrc/app-config-common/HEAD/production-microservice-common.yaml"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )
    }
  }
}
