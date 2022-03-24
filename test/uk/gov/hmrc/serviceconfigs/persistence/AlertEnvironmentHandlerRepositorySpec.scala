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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.AlertEnvironmentHandler

import scala.concurrent.ExecutionContext.Implicits.global

class AlertEnvironmentHandlerRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[AlertEnvironmentHandler] {

  override protected val repository = new AlertEnvironmentHandlerRepository(mongoComponent)

  "AlertEnvironmentHandlerRepository" should {
    "insert correctly" in {

      val alertEnvironmentHandler = AlertEnvironmentHandler("testName", production = true)

      repository.insert(Seq(alertEnvironmentHandler)).futureValue
      repository.findAll().futureValue should contain(alertEnvironmentHandler)
    }

    "find one by matching service name" in {
      val alertEnvironmentHandlers = Seq(AlertEnvironmentHandler("testNameOne", production = true),
        AlertEnvironmentHandler("testNameTwo", production = true))

      val expectedResult = Option(AlertEnvironmentHandler("testNameOne", production = true))

      repository.insert(alertEnvironmentHandlers).futureValue
      repository.findOne("testNameOne").futureValue shouldBe expectedResult
    }

    "delete all correctly" in {
      val alertEnvironmentHandler = AlertEnvironmentHandler("testName", production = true)

        (for {
           _          <- repository.insert(Seq(alertEnvironmentHandler))
           preDelete  <- repository.findAll()
           _          =  preDelete should contain(alertEnvironmentHandler)
           _          <- repository.deleteAll()
           postDelete <- repository.findAll()
           _          =  postDelete shouldBe empty
         } yield ()
        ).futureValue
    }
  }
}
