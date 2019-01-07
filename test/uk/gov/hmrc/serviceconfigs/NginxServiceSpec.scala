/*
 * Copyright 2019 HM Revenue & Customs
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
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{FlatSpec, Matchers}
import uk.gov.hmrc.serviceconfigs.connector.NginxConfigConnector
import uk.gov.hmrc.serviceconfigs.model.NginxConfigFile
import uk.gov.hmrc.serviceconfigs.parser.NginxConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.serviceconfigs.persistence.{FrontendRouteRepo, MongoConnector, MongoLock}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}


class NginxServiceSpec extends FlatSpec with Matchers with MockitoSugar {

  "joinConfigs" should "join multiple parsed routes into a single list" in {
    val configs = Seq(
      Seq(MongoFrontendRoute("test1", "/", "http://www.com", "dev")),
      Seq(MongoFrontendRoute("test1", "/", "http://www.com", "prod"), MongoFrontendRoute("test1", "/test", "http://www.com", "prod")))

    NginxService.joinConfigs(configs).length shouldBe 3
  }


  "update" should "parse configs and save result" in {

    val repo = mock[FrontendRouteRepo]
    val parser = new NginxConfigParser
    val connector = mock[NginxConfigConnector]
    val lock = new MongoLock(mock[MongoConnector]) {
      override def tryLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
        body.map(t => Some(t))
    }

    val service = new NginxService(repo, parser, connector, lock)


    when(connector.configFor("production")).thenReturn(Future {
      Some(NginxConfigFile(environment = "production", "", testConfig))
    })

    when(connector.configFor("development")).thenReturn(Future {
      None
    })

    when(repo.update(any())).thenReturn(Future(MongoFrontendRoute("","","","")))

    val envs = Seq("production", "development")
    service.update(envs)

    verify(repo, times(2)).update(any())
    verify(connector, times(envs.length)).configFor(any())
  }


  "parseConfig" should "turn an nginx config file into an indexed list of mongofrontendroutes" in {

    val parser = new NginxConfigParser()

    val configFile = NginxConfigFile(environment = "dev", "", testConfig)
    val result = NginxService.parseConfig(parser, configFile)

    result.length shouldBe 2

    result.head.environment  shouldBe "dev"
    result.head.service      shouldBe "service1"
    result.head.frontendPath shouldBe "/test/assets"
    result.head.backendPath  shouldBe "http://service1"
    result.head.ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/master/dev/frontend-proxy-application-rules.conf#L1"

    result(1).environment  shouldBe "dev"
    result(1).service      shouldBe "testservice"
    result(1).frontendPath shouldBe "/lol"
    result(1).backendPath  shouldBe "http://testservice"
    result(1).ruleConfigurationUrl shouldBe "https://github.com/hmrc/mdtp-frontend-routes/blob/master/dev/frontend-proxy-application-rules.conf#L7"

  }


  val testConfig = """location /test/assets {
                 |  more_set_headers 'X-Frame-Options: DENY';
                 |  more_set_headers 'X-XSS-Protection: 1; mode=block';
                 |  more_set_headers 'X-Content-Type-Options: nosniff';
                 |  proxy_pass http://service1;
                 |}
                 |location /lol {
                 |  more_set_headers '';
                 |  proxy_pass http://testservice;
                 |}""".stripMargin

}
