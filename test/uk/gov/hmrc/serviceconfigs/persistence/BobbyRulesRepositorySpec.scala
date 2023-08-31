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
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, BobbyRules}

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global

class BobbyRulesRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[BobbyRules] {

  override protected val repository = new BobbyRulesRepository(mongoComponent)

  "BobbyRulesRepository" should {
    "putAll correctly" in {
      locally {
        val config = BobbyRules(
          libraries = Seq(BobbyRule(
            organisation = "uk.gov.hmrc",
            name         = "name1",
            range        = "(,3.1.0)",
            reason       = "reason1",
            from         = LocalDate.parse("2015-11-01"),
            exemptProjects = Some(Seq("service-one-frontend", "service-two-front-end"))
          )),
          plugins   = Seq(BobbyRule(
            organisation = "uk.gov.hmrc",
            name         = "name2",
            range        = "(,3.2.0)",
            reason       = "reason2",
            from         = LocalDate.parse("2015-11-02")
          ))
        )
        repository.putAll(config).futureValue
        repository.findAll().futureValue shouldBe config
      }

      locally {
        val config = BobbyRules(
          libraries = Seq(BobbyRule(
              organisation = "uk.gov.hmrc",
              name         = "name3",
              range        = "(,3.3.0)",
              reason       = "reason3",
              from         = LocalDate.parse("2015-11-03")
            ),
            BobbyRule(
              organisation = "uk.gov.hmrc",
              name         = "name4",
              range        = "(,3.4.0)",
              reason       = "reason4",
              from         = LocalDate.parse("2015-11-04")
            )),
          plugins   = Seq.empty
        )
        repository.putAll(config).futureValue
        repository.findAll().futureValue shouldBe config
      }
    }
  }
}
