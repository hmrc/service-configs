/*
 * Copyright 2020 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito._
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.lock.MongoLockRepository
import uk.gov.hmrc.serviceconfigs.config.{NginxConfig, NginxShutterConfig}
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.NginxConfigFile
import uk.gov.hmrc.serviceconfigs.parser.NginxConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoShutterSwitch
import uk.gov.hmrc.serviceconfigs.persistence.{FrontendRouteRepository, MongoLock}
import uk.gov.hmrc.serviceconfigs.service.NginxService

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class NginxServiceSpec extends AnyFlatSpec with Matchers with MockitoSugar with ScalaFutures {

  private val nginxConfig   = mock[NginxConfig]
  private val shutterConfig = NginxShutterConfig("killswitch", "serviceswitch")
  private val routesFileUrl =
    "https://github.com/hmrc/mdtp-frontend-routes/blob/master/development/frontend-proxy-application-rules.conf"
  when(nginxConfig.frontendConfigFileNames)
    .thenReturn(List("some-file"))
  when(nginxConfig.shutterConfig)
    .thenReturn(shutterConfig)

  "urlToService" should "extract the service name from url" in {
    val url = "https://test-service.public.local"
    NginxService.urlToService(url) shouldBe "test-service"
  }

  it should "return the input unmodified if its not a url" in {
    val url = "test-service"
    NginxService.urlToService(url) shouldBe "test-service"
  }

  it should "handle hostnames without dots" in {
    val url = "https://test-service/test124?query=false"
    NginxService.urlToService(url) shouldBe "test-service"

  }

  "update" should "parse configs and save result" in {

    val repo = mock[FrontendRouteRepository]

    val parser    = new NginxConfigParser(nginxConfig)
    val connector = mock[NginxConfigConnector]

    val service = new NginxService(repo, parser, connector, nginxConfig)

    when(connector.getNginxRoutesFile("some-file", "production"))
      .thenReturn(Future.successful(
        NginxConfigFile(environment = "production", "", testConfig, branch = "master")
      ))

    when(repo.deleteByEnvironment(any()))
      .thenReturn(Future(true))
    when(repo.update(any()))
      .thenReturn(Future.successful(()))

    val envs = List("production", "development")
    service.update(envs).futureValue

    verify(repo, times(2)).update(any())
    verify(connector, times(envs.length)).getNginxRoutesFile(any(), any())
  }

  "parseConfig" should "turn an nginx config file into an indexed list of mongofrontendroutes" in {

    when(nginxConfig.shutterConfig)
      .thenReturn(NginxShutterConfig("/etc/nginx/switches/mdtp/offswitch", "/etc/nginx/switches/mdtp/"))
    val parser = new NginxConfigParser(nginxConfig)

    val configFile = NginxConfigFile(environment = "dev", routesFileUrl, testConfig, branch = "master")
    val eResult    = NginxService.parseConfig(parser, configFile)

    eResult.isRight shouldBe true
    val Right(result) = eResult
    result.length shouldBe 2

    result.head.environment          shouldBe "dev"
    result.head.service              shouldBe "service1"
    result.head.frontendPath         shouldBe "/test/assets"
    result.head.backendPath          shouldBe "http://service1"
    result.head.ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/master/dev/frontend-proxy-application-rules.conf#L1"
    result.head.markerComments       shouldBe Set.empty
    result.head.shutterKillswitch shouldBe Some(
      MongoShutterSwitch("/etc/nginx/switches/mdtp/offswitch", Some(503), None, None))
    result.head.shutterServiceSwitch shouldBe Some(
      MongoShutterSwitch("/etc/nginx/switches/mdtp/service1", Some(503), Some("/shutter/service1/index.html"), None))

    result(1).environment          shouldBe "dev"
    result(1).service              shouldBe "testservice"
    result(1).frontendPath         shouldBe "/lol"
    result(1).backendPath          shouldBe "http://testservice"
    result(1).ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/master/dev/frontend-proxy-application-rules.conf#L17"
    result(1).markerComments       shouldBe Set("NOT_SHUTTERABLE", "ABC")
    result(1).shutterKillswitch    shouldBe None
    result(1).shutterServiceSwitch shouldBe None
  }

  "parseConfig" should "ignore general comments" in {

    val parser = new NginxConfigParser(nginxConfig)

    val config = """location /lol {
                       |  #this is a comment
                       |  # here is another comment...
                       |  more_set_headers '';
                       |  proxy_pass http://testservice;
                       |}""".stripMargin

    val configFile = NginxConfigFile(environment = "dev", routesFileUrl, config, branch = "master")
    val eResult    = NginxService.parseConfig(parser, configFile)

    eResult.isRight shouldBe true
    val Right(result) = eResult
    result.length shouldBe 1

    result.head.environment          shouldBe "dev"
    result.head.service              shouldBe "testservice"
    result.head.frontendPath         shouldBe "/lol"
    result.head.backendPath          shouldBe "http://testservice"
    result.head.ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/master/dev/frontend-proxy-application-rules.conf#L1"
    result.head.markerComments       shouldBe Set.empty
    result.head.shutterKillswitch    shouldBe None
    result.head.shutterServiceSwitch shouldBe None
  }

  private val testConfig = """location /test/assets {
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

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(5.seconds)
}
