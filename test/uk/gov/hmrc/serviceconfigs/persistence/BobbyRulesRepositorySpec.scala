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

import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global

class BobbyRulesRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[String] {

  override protected val repository = new BobbyRulesRepository(mongoComponent)

  "BobbyRulesRepository" should {
    "putAll correctly" in {
      locally {
        val config = """[
          {
            "organisation": "uk.gov.hmrc",
            "name"        : "name1",
            "range"       : "(,3.2.0)",
            "reason"      : "reason1",
            "from"        : "2015-11-16"
          }
        ]"""
        repository.putAll(config).futureValue
        repository.findAll().futureValue shouldBe config
      }

      locally {
        val config = """[
          {
            "organisation": "uk.gov.hmrc",
            "name"        : "name2",
            "range"       : "(,3.2.0)",
            "reason"      : "reason2",
            "from"        : "2015-11-16"
          }
        ]"""
        repository.putAll(config).futureValue
        repository.findAll().futureValue shouldBe config
      }
    }
  }
}
