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
import uk.gov.hmrc.serviceconfigs.model.{CommitId, Environment, FileName, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class ConfigConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val token = "token"

  private val githubConfig = new GithubConfig(Configuration(
    "github.open.api.apiurl" -> wireMockUrl,
    "github.open.api.rawurl" -> wireMockUrl,
    "github.open.api.token"  -> token
  ))

  private val connector = new ConfigConnector(httpClientV2, githubConfig)

  "ConfigConnector" should {
    "retrieve appConfigEnvYaml" in {
      stubFor(
        get(urlEqualTo("/hmrc/app-config-production/1234/service.yaml"))
          .willReturn(aResponse().withBody("content"))
      )

      connector.appConfigEnvYaml(Environment.Production, ServiceName("service"), CommitId("1234")).futureValue shouldBe Some(
        "content"
      )

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/hmrc/app-config-production/1234/service.yaml"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )
    }

    "retrieve appConfigBaseConf" in {
      stubFor(
        get(urlEqualTo("/hmrc/app-config-base/1234/service.conf"))
          .willReturn(aResponse().withBody("content"))
      )

      connector.appConfigBaseConf(ServiceName("service"), CommitId("1234")).futureValue shouldBe Some(
        "content"
      )

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/hmrc/app-config-base/1234/service.conf"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )
    }

    "retrieve appConfigCommonYaml" in {
      stubFor(
        get(urlEqualTo("/hmrc/app-config-common/1234/production-frontend-common.yaml"))
          .willReturn(aResponse().withBody("content"))
      )

      connector.appConfigCommonYaml(FileName("production-frontend-common.yaml"), CommitId("1234")).futureValue shouldBe Some(
        "content"
      )

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/hmrc/app-config-common/1234/production-frontend-common.yaml"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )
    }

    "retrieve appConfigCommonYaml without 'api-'' in name" in {
      stubFor(
        get(urlEqualTo("/hmrc/app-config-common/1234/production-microservice-common.yaml"))
          .willReturn(aResponse().withBody("content"))
      )

      connector.appConfigCommonYaml(FileName("production-api-microservice-common.yaml"), CommitId("1234")).futureValue shouldBe Some(
        "content"
      )
    }

    "retrieve applicationConf" in {
      stubFor(
        get(urlEqualTo("/hmrc/service/1234/conf/application.conf"))
          .willReturn(aResponse().withBody("content"))
      )

      connector.applicationConf(ServiceName("service"), CommitId("1234")).futureValue shouldBe Some(
        "content"
      )

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/hmrc/service/1234/conf/application.conf"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )
    }
  }
}
