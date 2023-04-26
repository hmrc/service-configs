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

package uk.gov.hmrc.serviceconfigs.controller

import uk.gov.hmrc.serviceconfigs.model.Environment._
import uk.gov.hmrc.serviceconfigs.service._

import akka.actor.ActorSystem
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class OutagePageControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  "searchByServiceName" should {
    "return list of environments" in new Setup {
      val serviceName = "service-name"
      when(mockOutagePageService.findByServiceName(serviceName))
        .thenReturn(Future.successful(
          Some(List(QA, Production))
        ))

      val response = call(
        controller.searchByServiceName(serviceName),
        FakeRequest(GET, s"/outage-pages/$serviceName")
      )

      status(response) shouldBe 200
      contentAsJson(response) shouldBe Json.parse(
        s"""["qa", "production"]"""
      )
    }
    "return not found" in new Setup{
      val serviceName = "not-found-service-name"
      when(mockOutagePageService.findByServiceName(serviceName))
        .thenReturn(Future.successful(None))

      val response = call(
        controller.searchByServiceName(serviceName),
        FakeRequest(GET, s"/outage-pages/$serviceName")
      )

      status(response) shouldBe 404
    }
  }

  trait Setup {
    implicit val as = ActorSystem()

    val mockOutagePageService = mock[OutagePageService]

    val controller = new OutagePageController(
      outagePageService = mockOutagePageService,
      cc                = stubControllerComponents()
    )
  }
}
