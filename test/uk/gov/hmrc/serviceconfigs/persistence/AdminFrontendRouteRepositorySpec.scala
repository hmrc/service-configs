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

package uk.gov.hmrc.serviceconfigs.persistence

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{AdminFrontendRoute, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class AdminFrontendRouteRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[AdminFrontendRoute] {

  override protected val repository: AdminFrontendRouteRepository = new AdminFrontendRouteRepository(mongoComponent)

  "AdminFrontendRouteRepository" should {
    "putAll correctly" in {
      val adminFrontendRoute1 = AdminFrontendRoute(ServiceName("testNameOne"), route = "route1", allow = Map.empty, location = "location1")
      val adminFrontendRoute2 = AdminFrontendRoute(ServiceName("testNameOne"), route = "route2", allow = Map.empty, location = "location2")
      val adminFrontendRoute3 = AdminFrontendRoute(ServiceName("testNameTwo"), route = "route3", allow = Map.empty, location = "location3")
      repository.putAll(Seq(adminFrontendRoute1, adminFrontendRoute2, adminFrontendRoute3)).futureValue
      findAll().futureValue shouldBe Seq(adminFrontendRoute1, adminFrontendRoute2, adminFrontendRoute3)

      val adminFrontendRoute4 = AdminFrontendRoute(ServiceName("testNameOne"), route = "route3", allow = Map.empty, location = "location2")
      repository.putAll(Seq(adminFrontendRoute2, adminFrontendRoute4)).futureValue
      findAll().futureValue shouldBe Seq(adminFrontendRoute2, adminFrontendRoute4)
    }

    "find one by matching service name" in {
      val adminFrontendRoute1 = AdminFrontendRoute(ServiceName("testNameOne"), route = "route1", allow = Map.empty, location = "location1")
      val adminFrontendRoute2 = AdminFrontendRoute(ServiceName("testNameOne"), route = "route2", allow = Map.empty, location = "location2")
      val adminFrontendRoute3 = AdminFrontendRoute(ServiceName("testNameTwo"), route = "route3", allow = Map.empty, location = "location3")
      repository.putAll(Seq(adminFrontendRoute1, adminFrontendRoute2, adminFrontendRoute3)).futureValue
      repository.findByService(ServiceName("testNameOne")).futureValue shouldBe Seq(adminFrontendRoute1 , adminFrontendRoute2)
    }

    "return all service names" in {
      val adminFrontendRoute1 = AdminFrontendRoute(ServiceName("testNameOne"), route = "route1", allow = Map.empty, location = "location1")
      val adminFrontendRoute2 = AdminFrontendRoute(ServiceName("testNameOne"), route = "route2", allow = Map.empty, location = "location2")
      val adminFrontendRoute3 = AdminFrontendRoute(ServiceName("testNameTwo"), route = "route3", allow = Map.empty, location = "location3")
      repository.putAll(Seq(adminFrontendRoute1, adminFrontendRoute2, adminFrontendRoute3)).futureValue
      repository.findAllAdminFrontendServices().futureValue shouldBe Seq(ServiceName("testNameOne") , ServiceName("testNameTwo"))
    }
  }
}
