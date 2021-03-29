/*
 * Copyright 2021 HM Revenue & Customs
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

import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.ws.WSClient
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.AlertEnvironmentHandler
import uk.gov.hmrc.serviceconfigs.persistence.AlertEnvironmentHandlerRepository
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder

import scala.concurrent.Future

class AlertConfigIntegrationSpec
  extends AnyWordSpec
    with DefaultPlayMongoRepositorySupport[AlertEnvironmentHandler]
    with GuiceOneServerPerSuite
    //with WireMockEndpoints
    with Matchers
    with ScalaFutures
    with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(1000, Millis)))

  protected val repository: AlertEnvironmentHandlerRepository = app.injector.instanceOf[AlertEnvironmentHandlerRepository]
  private[this] lazy val ws                                   = app.injector.instanceOf[WSClient]


  override def fakeApplication: Application =
    new GuiceApplicationBuilder()
      .disable(classOf[com.kenshoo.play.metrics.PlayModule])
      .configure(
        Map(
          "mongodb.uri"                                       -> mongoUri,
          "metrics.jvm"                                       -> false
        )
      )
      .build()

  "Alert Config Controller" should {
    "return 200 when it starts correctly and receives GET /ping/ping" in {
      val response = ws.url(s"http://localhost:$port/ping/ping").get.futureValue

      response.status shouldBe 200
    }

    "return correct json when getAlertConfigs receives a get request" in {

      val testData = Seq(AlertEnvironmentHandler("testNameTwo", true),
        AlertEnvironmentHandler("testNameOne", true))

      repository.insert(testData)

      val expectedResultOne = """{"serviceName":"testNameOne","production":true}"""
      val expectedResultTwo = """{"serviceName":"testNameTwo","production":true}"""


      eventually {
        val response = ws.url(s"http://localhost:$port/service-configs/alert-configs").get.futureValue

        response.status shouldBe 200
        response.body should include(expectedResultOne)
        response.body should include(expectedResultTwo)
      }
    }

    "return correct json when getAlertConfigForService receives a get request" in {

      val testData = Seq(AlertEnvironmentHandler("testNameOne", true),
        AlertEnvironmentHandler("testNameTwo", true))

      repository.insert(testData)

      val expectedResult = """{"serviceName":"testNameOne","production":true}"""

      eventually {
        val response =  ws.url(s"http://localhost:$port/service-configs/alert-configs/testNameOne").get.futureValue

        response.status shouldBe 200
        response.body should include(expectedResult)
      }

    }

    "return NotFound when getAlertConfigForService receives a get request for a non-existing service" in {

      val testData = Seq(AlertEnvironmentHandler("testNameOne", true),
        AlertEnvironmentHandler("testNameTwo", true))

      repository.insert(testData)

      eventually {
        val response =  ws.url(s"http://localhost:$port/service-configs/alert-configs/testNameNonExisting").get.futureValue

        response.status shouldBe 404
        response.body shouldBe ""
      }
    }

    "return NotFound when getAlertConfigs receives a get request and the repository has no data" in {

      val response =  ws.url(s"http://localhost:$port/service-configs/alert-configs/testNameOne").get.futureValue

      response.status shouldBe 404
      response.body shouldBe ""
    }

  }
}
