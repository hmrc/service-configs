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

import java.time.Instant

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, ServiceName, SlugInfo, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class ArtefactProcessorConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support {

  private given HeaderCarrier = HeaderCarrier()

  private val servicesConfig = new ServicesConfig(Configuration(
    "microservice.services.artefact-processor.host" -> wireMockHost,
    "microservice.services.artefact-processor.port" -> wireMockPort,
  ))

  private val connector = new ArtefactProcessorConnector(httpClientV2, servicesConfig)

  "ArtefactProcessorConnector.getSlugInfo" should {
    "correctly parse json response" in {
      stubFor(
        get(urlEqualTo(s"/result/slug/name/1.0.0"))
          .willReturn(aResponse().withBodyFile("artefact-processor/slug-info.json"))
      )

      connector.getSlugInfo("name", Version("1.0.0")).futureValue shouldBe Some(
        SlugInfo(
          created              = Instant.parse("2019-06-28T11:51:23Z"),
          uri                  = "https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz",
          name                 = ServiceName("my-slug"),
          version              = Version.apply("0.27.0"),
          classpath            = "some-classpath",
          dependencies         = List.empty,
          applicationConfig    = "some-application-config",
          includedAppConfig    = Map.empty,
          loggerConfig         = "some-logger-config",
          slugConfig           = "some-slug-config"
        )
      )
    }
  }

  "ArtefactProcessorConnector.getDependencyConfigs" should {
    "correctly parse json response" in {
      stubFor(
        get(urlEqualTo(s"/slugInfoConfigs/name/1.0.0"))
          .willReturn(aResponse().withBodyFile("artefact-processor/dependency-configs.json"))
      )

      connector.getDependencyConfigs("name", Version("1.0.0")).futureValue shouldBe Some(Seq(
        DependencyConfig(
          group    = "uk.gov.hmrc",
          artefact = "time",
          version  = "3.2.0",
          configs  = Map(
                       "includes.conf" -> "a = 1",
                      "reference.conf" ->
                         """|include "includes.conf"
                            |
                            |b = 2""".stripMargin
                     )
        )
      ))
    }
  }
}
