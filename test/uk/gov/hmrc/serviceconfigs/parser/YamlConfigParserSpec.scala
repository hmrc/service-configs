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

package uk.gov.hmrc.serviceconfigs.parser

import org.mockito.scalatest.MockitoSugar
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.JsResultException
import uk.gov.hmrc.serviceconfigs.config.{NginxConfig, NginxShutterConfig}
import uk.gov.hmrc.serviceconfigs.model.YamlRoutesFile

class YamlConfigParserSpec
  extends AnyWordSpec
    with Matchers
    with MockitoSugar
    with OptionValues {

  val nginxConfig = mock[NginxConfig]
  val shutterConfig = NginxShutterConfig("/etc/nginx/switches/mdtp/offswitch", "/etc/nginx/switches/mdtp/")
  when(nginxConfig.shutterConfig).thenReturn(shutterConfig)

  val parser = new YamlConfigParser(nginxConfig)

  def fileWithContent(content: String): YamlRoutesFile =
    YamlRoutesFile(
      "https://test.com/config/routes.yaml",
      "https://test.com/blob/config/routes.yaml",
      content,
      "HEAD"
    )

  "parseConfig" should {
    "produce one output environment using defaults for shuttering and zone" in {
      val content =
        """
          |my-service-frontend:
          |  environments:
          |    qa:
          |      - path: /my-service
          |    production:
          |      - path: /my-service
          |""".stripMargin

      val result = parser.parseConfig(fileWithContent(content))

      result.size shouldBe 2

      result.map { r =>
        r.service              shouldBe "my-service-frontend"
        r.frontendPath         shouldBe "/my-service"
        r.backendPath          shouldBe "https://my-service-frontend.public.mdtp" // zone defaults to public
        r.routesFile           shouldBe "routes.yaml"
        r.shutterKillswitch
          .value.switchFile    shouldBe "/etc/nginx/switches/mdtp/offswitch" // global shuttering enabled by default
        r.shutterServiceSwitch
          .value.switchFile    shouldBe "/etc/nginx/switches/mdtp/my-service-frontend" // enabled by default
        r.shutterServiceSwitch
          .value.errorPage     shouldBe Some("/shuttered$LANG/my-service-frontend") // enabled by default
        r.isRegex              shouldBe false
      }
    }

    "detect and handle locations with regex" in {
      val content =
        """
          |my-service-frontend:
          |  environments:
          |    production:
          |      - path: ~ ^/(my-service|hello-world)
          |""".stripMargin

      val result = parser.parseConfig(fileWithContent(content))

      result.size shouldBe 1

      result.head.service      shouldBe "my-service-frontend"
      result.head.frontendPath shouldBe "^/(my-service|hello-world)"
      result.head.isRegex      shouldBe true
    }

    "handle a service that doesn't support shuttering (incl non-default zone)" in {
      val content =
        """
          |my-service-frontend:
          |  platform-off-switch: false
          |  zone: public-monolith
          |  environments:
          |    production:
          |      - path: /my-service
          |        shutterable: false
          |""".stripMargin

      val result = parser.parseConfig(fileWithContent(content))

      result.size shouldBe 1

      result.head.service              shouldBe "my-service-frontend"
      result.head.frontendPath         shouldBe "/my-service"
      result.head.backendPath          shouldBe "https://my-service-frontend.public-monolith.mdtp"
      result.head.shutterKillswitch    shouldBe None
      result.head.shutterServiceSwitch shouldBe None
      result.head.isRegex              shouldBe false
    }

    "handle a service with multiple locations in an environment" in {
      val content =
        """
          |my-service-frontend:
          |  environments:
          |    production:
          |      - path: /my-service
          |      - path: /hello-world
          |""".stripMargin

      val result = parser.parseConfig(fileWithContent(content))

      result.size shouldBe 2

      result.map(_.frontendPath) should contain theSameElementsAs Seq("/my-service", "/hello-world")
    }

    "throw an exception given bad yaml" in {
      val content =
        """
          |foo
          |bar
          |fizz
          |bang
          |""".stripMargin

      a[JsResultException] should be thrownBy parser.parseConfig(fileWithContent(content))
    }
  }

}
