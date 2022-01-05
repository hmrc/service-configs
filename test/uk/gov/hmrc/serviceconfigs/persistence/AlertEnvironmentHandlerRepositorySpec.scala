/*
 * Copyright 2022 HM Revenue & Customs
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

import org.mockito.MockitoSugar
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.AlertEnvironmentHandler

import scala.concurrent.ExecutionContext.Implicits.global

class AlertEnvironmentHandlerRepositorySpec
  extends AnyWordSpec
    with Matchers
    with MockitoSugar
    with Eventually
    with DefaultPlayMongoRepositorySupport[AlertEnvironmentHandler] {


  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(1000, Millis)))

  override protected val repository = new AlertEnvironmentHandlerRepository(mongoComponent)

  "AlertEnvironmentHandlerRepository" should {


    "insert correctly" in {

      val alertEnvironmentHandler = AlertEnvironmentHandler("testName", production = true)

      eventually {
        repository.insert(Seq(alertEnvironmentHandler))
        repository.findAll().futureValue must contain(alertEnvironmentHandler)
      }
    }

    "find one by matching service name" in {

      val alertEnvironmentHandlers = Seq(AlertEnvironmentHandler("testNameOne", production = true),
        AlertEnvironmentHandler("testNameTwo", production = true))

      val expectedResult = Option(AlertEnvironmentHandler("testNameOne", production = true))

      eventually {
        repository.insert(alertEnvironmentHandlers)
        repository.findOne("testNameOne").futureValue mustBe expectedResult
      }
    }

    "delete all correctly" in {

      val alertEnvironmentHandler = AlertEnvironmentHandler("testName", production = true)

      eventually {
        repository.insert(Seq(alertEnvironmentHandler))
        repository.findAll().futureValue must contain(alertEnvironmentHandler)
        repository.deleteAll
        repository.findAll().futureValue.isEmpty mustBe true
      }

    }

  }
}


