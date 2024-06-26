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

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, spy, verify, when}
import org.scalatest.EitherValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.serviceconfigs.config.{GithubConfig, NginxConfig, NginxShutterConfig}
import uk.gov.hmrc.serviceconfigs.connector.{ConfigAsCodeConnector, NginxConfigConnector}
import uk.gov.hmrc.serviceconfigs.model.{Environment, NginxConfigFile, ServiceName}
import uk.gov.hmrc.serviceconfigs.parser.{NginxConfigParser, YamlConfigParser}
import uk.gov.hmrc.serviceconfigs.persistence.FrontendRouteRepository
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoShutterSwitch

import java.util.zip.ZipInputStream
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class NginxServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience
     with EitherValues:

  private val nginxConfig   = mock[NginxConfig]
  private val shutterConfig = NginxShutterConfig("killswitch", "serviceswitch")
  private val routesFileUrl = "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf"

  when(nginxConfig.frontendConfigFileNames)
    .thenReturn(List("some-file"))

  when(nginxConfig.shutterConfig)
    .thenReturn(shutterConfig)

  val githubConfig: GithubConfig = mock[GithubConfig]

  when(githubConfig.githubApiUrl)
    .thenReturn("https://api.github.com")

  when(githubConfig.githubRawUrl)
    .thenReturn("https://raw.githubusercontent.com")

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

  "NginxService.urlToService" should:
    "extract the service name from url" in:
      val url = "https://test-service.public.local"
      NginxService.urlToService(url) shouldBe ServiceName("test-service")

    "return the input unmodified if its not a url" in:
      val url = "test-service"
      NginxService.urlToService(url) shouldBe ServiceName("test-service")

    "handle hostnames without dots" in:
      val url = "https://test-service/test124?query=false"
      NginxService.urlToService(url) shouldBe ServiceName("test-service")

  "NginxService.update" should:
    "parse configs and save result" in:
      val repo = mock[FrontendRouteRepository]

      val parser          = NginxConfigParser(nginxConfig)
      val yamlParser      = spy(YamlConfigParser(nginxConfig))
      val nginxConnector  = mock[NginxConfigConnector]
      val cacConnector    = mock[ConfigAsCodeConnector]

      val service = NginxService(repo, parser, yamlParser, nginxConnector, cacConnector, nginxConfig, githubConfig)

      when(nginxConnector.getNginxRoutesFile("some-file", Environment.Production))
        .thenReturn(Future.successful(
          NginxConfigFile(environment = Environment.Production, "", testConfig, branch = "HEAD")
        ))

      when(cacConnector.streamFrontendRoutes())
        .thenReturn(Future.successful(
          ZipInputStream(this.getClass.getResource("/empty-mdtp-frontend-routes.zip").openStream())
        ))

      when(repo.replaceEnv(any, any))
        .thenReturn(Future.unit)

      val envs = List(Environment.Production, Environment.Development)
      service.update(envs).futureValue

      verify(repo, times(1)).replaceEnv(any, any)
      verify(nginxConnector, times(envs.length)).getNginxRoutesFile(any, any)
      verify(cacConnector, times(1)).streamFrontendRoutes()
      verify(yamlParser, times(0)).parseConfig(any)

    "parse configs from nginx and yaml and save the result" in:
      val repo = mock[FrontendRouteRepository]

      val parser         = NginxConfigParser(nginxConfig)
      val yamlParser     = spy(YamlConfigParser(nginxConfig))
      val nginxConnector = mock[NginxConfigConnector]
      val cacConnector   = mock[ConfigAsCodeConnector]

      val service = NginxService(repo, parser, yamlParser, nginxConnector, cacConnector, nginxConfig, githubConfig)

      when(nginxConnector.getNginxRoutesFile("some-file", Environment.Production))
        .thenReturn(Future.successful(
          NginxConfigFile(environment = Environment.Production, "", testConfig, branch = "HEAD")
        ))

      when(cacConnector.streamFrontendRoutes())
        .thenReturn(Future.successful(
          ZipInputStream(this.getClass.getResource("/mdtp-frontend-routes.zip").openStream())
        ))

      when(repo.replaceEnv(any, any))
        .thenReturn(Future.unit)

      service.update(List(Environment.Production)).futureValue

      verify(repo, times(1)).replaceEnv(any, any)
      verify(nginxConnector, times(1)).getNginxRoutesFile(any, any)
      verify(cacConnector, times(1)).streamFrontendRoutes()
      verify(yamlParser, times(3)).parseConfig(any) // there's 3 yaml files in mdtp-frontend-routes.zip

  "NginxService.parseConfig" should:
    "turn an nginx config file into an indexed list of mongofrontendroutes" in:
      when(nginxConfig.shutterConfig)
        .thenReturn(NginxShutterConfig("/etc/nginx/switches/mdtp/offswitch", "/etc/nginx/switches/mdtp/"))

      val parser     = NginxConfigParser(nginxConfig)
      val configFile = NginxConfigFile(environment = Environment.Development, routesFileUrl, testConfig, branch = "HEAD")
      val result     = NginxService.parseConfig(parser, configFile).value

      result.length shouldBe 2

      result.head.environment          shouldBe Environment.Development
      result.head.service              shouldBe ServiceName("service1")
      result.head.frontendPath         shouldBe "/test/assets"
      result.head.backendPath          shouldBe "http://service1"
      result.head.ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf#L1"
      result.head.markerComments       shouldBe Set.empty
      result.head.shutterKillswitch shouldBe Some(
        MongoShutterSwitch("/etc/nginx/switches/mdtp/offswitch", Some(503), None, None))
      result.head.shutterServiceSwitch shouldBe Some(
        MongoShutterSwitch("/etc/nginx/switches/mdtp/service1", Some(503), Some("/shutter/service1/index.html"), None))

      result(1).environment          shouldBe Environment.Development
      result(1).service              shouldBe ServiceName("testservice")
      result(1).frontendPath         shouldBe "/lol"
      result(1).backendPath          shouldBe "http://testservice"
      result(1).ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf#L17"
      result(1).markerComments       shouldBe Set("NOT_SHUTTERABLE", "ABC")
      result(1).shutterKillswitch    shouldBe None
      result(1).shutterServiceSwitch shouldBe None

    "ignore general comments" in:
      val parser = NginxConfigParser(nginxConfig)
      val config = """location /lol {
                        |  #this is a comment
                        |  # here is another comment...
                        |  more_set_headers '';
                        |  proxy_pass http://testservice;
                        |}""".stripMargin

      val configFile = NginxConfigFile(environment = Environment.Development, routesFileUrl, config, branch = "HEAD")
      val result     = NginxService.parseConfig(parser, configFile).value

      result.length shouldBe 1

      result.head.environment          shouldBe Environment.Development
      result.head.service              shouldBe ServiceName("testservice")
      result.head.frontendPath         shouldBe "/lol"
      result.head.backendPath          shouldBe "http://testservice"
      result.head.ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf#L1"
      result.head.markerComments       shouldBe Set.empty
      result.head.shutterKillswitch    shouldBe None
      result.head.shutterServiceSwitch shouldBe None
