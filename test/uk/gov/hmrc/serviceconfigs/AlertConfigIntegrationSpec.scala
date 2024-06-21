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

package uk.gov.hmrc.serviceconfigs

import org.mongodb.scala.SingleObservableFuture
import org.scalatest.concurrent.{Eventually, IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.ws.DefaultBodyReadables.readableAsString
import play.api.libs.ws.WSClient
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.AlertEnvironmentHandlerRepository

class AlertConfigIntegrationSpec
  extends AnyWordSpec
     with DefaultPlayMongoRepositorySupport[AlertEnvironmentHandler]
     with GuiceOneServerPerSuite
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with Eventually:

  protected val repository: AlertEnvironmentHandlerRepository = app.injector.instanceOf[AlertEnvironmentHandlerRepository]

  private val wsClient = app.injector.instanceOf[WSClient]

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        Map(
          "mongodb.uri" -> mongoUri,
        )
      )
      .build()

  "Alert Config Controller" should:
    "return 200 when it starts correctly and receives GET /ping/ping" in:
      val response = wsClient.url(s"http://localhost:$port/ping/ping").get().futureValue
      response.status shouldBe 200

    "return correct json when getAlertConfigs receives a get request" in:
      val testData = Seq(
        AlertEnvironmentHandler(ServiceName("testNameTwo"), true, "L2"),
        AlertEnvironmentHandler(ServiceName("testNameOne"), true, "L1")
      )

      repository.collection.insertMany(testData).toFuture().futureValue

      val expectedResultOne = """{"serviceName":"testNameOne","production":true,"location":"L1"}"""
      val expectedResultTwo = """{"serviceName":"testNameTwo","production":true,"location":"L2"}"""

      eventually:
        val response = wsClient.url(s"http://localhost:$port/service-configs/alert-configs").get().futureValue
        response.status shouldBe 200
        response.body should include(expectedResultOne)
        response.body should include(expectedResultTwo)

    "return correct json when getAlertConfigForService receives a get request" in:
      val testData =
        Seq(
          AlertEnvironmentHandler(ServiceName("testNameOne"), true, "L1"),
          AlertEnvironmentHandler(ServiceName("testNameTwo"), true, "L2")
        )

      repository.collection.insertMany(testData).toFuture().futureValue

      val expectedResult = """{"serviceName":"testNameOne","production":true,"location":"L1"}"""

      eventually:
        val response = wsClient.url(s"http://localhost:$port/service-configs/alert-configs/testNameOne").get().futureValue
        response.status shouldBe 200
        response.body should include(expectedResult)

    "return NotFound when getAlertConfigForService receives a get request for a non-existing service" in:
      val testData =
        Seq(
          AlertEnvironmentHandler(ServiceName("testNameOne"), true, ""),
          AlertEnvironmentHandler(ServiceName("testNameTwo"), true, "")
        )

      repository.collection.insertMany(testData).toFuture().futureValue

      eventually:
        val response = wsClient.url(s"http://localhost:$port/service-configs/alert-configs/testNameNonExisting").get().futureValue
        response.status shouldBe 404
        response.body shouldBe ""

    "return NotFound when getAlertConfigs receives a get request and the repository has no data" in:
      val response = wsClient.url(s"http://localhost:$port/service-configs/alert-configs/testNameOne").get().futureValue
      response.status shouldBe 404
      response.body shouldBe ""
