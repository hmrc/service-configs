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

package uk.gov.hmrc.serviceconfigs.service

import org.mockito.scalatest.MockitoSugar
import org.scalatest.EitherValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.config.{NginxConfig, NginxShutterConfig}
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{Environment, NginxConfigFile, YamlRoutesFile}
import uk.gov.hmrc.serviceconfigs.parser.{NginxConfigParser, YamlConfigParser}
import uk.gov.hmrc.serviceconfigs.persistence.FrontendRouteRepository
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoShutterSwitch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NginxServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with EitherValues {

  private val nginxConfig   = mock[NginxConfig]
  private val shutterConfig = NginxShutterConfig("killswitch", "serviceswitch")
  private val routesFileUrl = "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf"

  when(nginxConfig.frontendConfigFileNames)
    .thenReturn(List("some-file"))

  when(nginxConfig.shutterConfig)
    .thenReturn(shutterConfig)

  private val testConfig =
    """location /test/assets {
      |  more_set_headers 'X-Frame-Options: DENY';
      |  more_set_headers 'X-XSS-Protection: 1; mode=block';
      |  more_set_headers 'X-Content-Type-Options: nosniff';
      |
      |  if ( -f /etc/nginx/switches/mdtp/offswitch )   {
      |    return 503;
      |  }
      |
      |  if ( -f /etc/nginx/switches/mdtp/service1 )   {
      |    error_page 503 /shutter/service1/index.html;
      |    return 503;
      |  }
      |
      |  proxy_pass http://service1;
      |}
      |location /lol {
      |  #!NOT_SHUTTERABLE
      |  #!ABC
      |  #NOT_A_VALID_MARKER_COMMENT
      |  more_set_headers '';
      |  proxy_pass http://testservice;
      |}""".stripMargin

  private val testYamlConfig =
    """
      |a-service:
      |  environments:
      |  - production
      |  locations:
      |  - /hello
      |
      |b-service:
      |  environments:
      |  - production
      |  locations:
      |  - /goodbye
      |""".stripMargin

  "NginxService.urlToService" should {
    "extract the service name from url" in {
      val url = "https://test-service.public.local"
      NginxService.urlToService(url) shouldBe "test-service"
    }

    "return the input unmodified if its not a url" in {
      val url = "test-service"
      NginxService.urlToService(url) shouldBe "test-service"
    }

    "handle hostnames without dots" in {
      val url = "https://test-service/test124?query=false"
      NginxService.urlToService(url) shouldBe "test-service"
    }
  }

  "NginxService.update" should {
    "parse configs and save result" in {
      val repo = mock[FrontendRouteRepository]

      val parser     = new NginxConfigParser(nginxConfig)
      val yamlParser = new YamlConfigParser(nginxConfig)
      val connector  = mock[NginxConfigConnector]

      val service = new NginxService(repo, parser, yamlParser, connector, nginxConfig)

      when(connector.getNginxRoutesFile("some-file", Environment.Production))
        .thenReturn(Future.successful(
          NginxConfigFile(environment = Environment.Production, "", testConfig, branch = "HEAD")
        ))

      when(connector.getYamlRoutesFile())
        .thenReturn(Future.successful(
          YamlRoutesFile("", "", "", "")
        ))

      when(repo.replaceEnv(any, any))
        .thenReturn(Future.unit)

      val envs = List(Environment.Production, Environment.Development)
      service.update(envs).futureValue

      verify(repo, times(1)).replaceEnv(any, any)
      verify(connector, times(envs.length)).getNginxRoutesFile(any, any)
      verify(connector, times(1)).getYamlRoutesFile()
    }

    "parse configs from nginx and yaml and save the result" in {
      val repo = mock[FrontendRouteRepository]

      val parser = new NginxConfigParser(nginxConfig)
      val yamlParser = new YamlConfigParser(nginxConfig)
      val connector = mock[NginxConfigConnector]

      val service = new NginxService(repo, parser, yamlParser, connector, nginxConfig)

      when(connector.getNginxRoutesFile("some-file", Environment.Production))
        .thenReturn(Future.successful(
          NginxConfigFile(environment = Environment.Production, "", testConfig, branch = "HEAD")
        ))

      when(connector.getYamlRoutesFile())
        .thenReturn(Future.successful(
          YamlRoutesFile(
            "https://test.com/config/routes.yaml",
            "https://test.com/blob/config/routes.yaml",
            testYamlConfig,
            "HEAD"
          )
        ))

      when(repo.replaceEnv(any, any))
        .thenReturn(Future.unit)

      service.update(List(Environment.Production)).futureValue

      verify(repo, times(1)).replaceEnv(any, any)
      verify(connector, times(1)).getNginxRoutesFile(any, any)
      verify(connector, times(1)).getYamlRoutesFile()
    }
  }

  "NginxService.parseConfig" should {
    "turn an nginx config file into an indexed list of mongofrontendroutes" in {
      when(nginxConfig.shutterConfig)
        .thenReturn(NginxShutterConfig("/etc/nginx/switches/mdtp/offswitch", "/etc/nginx/switches/mdtp/"))

      val parser = new NginxConfigParser(nginxConfig)
      val configFile = NginxConfigFile(environment = Environment.Development, routesFileUrl, testConfig, branch = "HEAD")
      val result     = NginxService.parseConfig(parser, configFile).right.value

      result.length shouldBe 2

      result.head.environment          shouldBe Environment.Development
      result.head.service              shouldBe "service1"
      result.head.frontendPath         shouldBe "/test/assets"
      result.head.backendPath          shouldBe "http://service1"
      result.head.ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf#L1"
      result.head.markerComments       shouldBe Set.empty
      result.head.shutterKillswitch shouldBe Some(
        MongoShutterSwitch("/etc/nginx/switches/mdtp/offswitch", Some(503), None, None))
      result.head.shutterServiceSwitch shouldBe Some(
        MongoShutterSwitch("/etc/nginx/switches/mdtp/service1", Some(503), Some("/shutter/service1/index.html"), None))

      result(1).environment          shouldBe Environment.Development
      result(1).service              shouldBe "testservice"
      result(1).frontendPath         shouldBe "/lol"
      result(1).backendPath          shouldBe "http://testservice"
      result(1).ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf#L17"
      result(1).markerComments       shouldBe Set("NOT_SHUTTERABLE", "ABC")
      result(1).shutterKillswitch    shouldBe None
      result(1).shutterServiceSwitch shouldBe None
    }

    "ignore general comments" in {
      val parser = new NginxConfigParser(nginxConfig)

      val config = """location /lol {
                        |  #this is a comment
                        |  # here is another comment...
                        |  more_set_headers '';
                        |  proxy_pass http://testservice;
                        |}""".stripMargin

      val configFile = NginxConfigFile(environment = Environment.Development, routesFileUrl, config, branch = "HEAD")
      val result     = NginxService.parseConfig(parser, configFile).right.value

      result.length shouldBe 1

      result.head.environment          shouldBe Environment.Development
      result.head.service              shouldBe "testservice"
      result.head.frontendPath         shouldBe "/lol"
      result.head.backendPath          shouldBe "http://testservice"
      result.head.ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf#L1"
      result.head.markerComments       shouldBe Set.empty
      result.head.shutterKillswitch    shouldBe None
      result.head.shutterServiceSwitch shouldBe None
    }
  }
}
