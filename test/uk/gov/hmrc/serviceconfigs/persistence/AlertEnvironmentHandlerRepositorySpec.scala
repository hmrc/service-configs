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
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class AlertEnvironmentHandlerRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[AlertEnvironmentHandler] {

  override protected val repository: AlertEnvironmentHandlerRepository = new AlertEnvironmentHandlerRepository(mongoComponent)

  "AlertEnvironmentHandlerRepository" should {
    "putAll correctly" in {
      val alertEnvironmentHandler1 = AlertEnvironmentHandler(ServiceName("testNameOne"), production = true, location = "")
      repository.putAll(Seq(alertEnvironmentHandler1)).futureValue
      repository.findAll().futureValue shouldBe Seq(alertEnvironmentHandler1)

      val alertEnvironmentHandler2 = AlertEnvironmentHandler(ServiceName("testNameTwo"), production = false, location = "2")
      repository.putAll(Seq(alertEnvironmentHandler2)).futureValue
      repository.findAll().futureValue shouldBe Seq(alertEnvironmentHandler2)
    }

    "find one by matching service name" in {
      val alertEnvironmentHandlers = Seq(
        AlertEnvironmentHandler(ServiceName("testNameOne"), production = true, location = ""),
        AlertEnvironmentHandler(ServiceName("testNameTwo"), production = true, location = "")
      )
      repository.putAll(alertEnvironmentHandlers).futureValue
      repository.findByServiceName(ServiceName("testNameOne")).futureValue shouldBe alertEnvironmentHandlers.find(_.serviceName == ServiceName("testNameOne"))
    }
  }
}
