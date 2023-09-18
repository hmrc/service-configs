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
import uk.gov.hmrc.serviceconfigs.persistence.BobbyWarningsNotificationsRepository.BobbyWarningsNotificationsRunDate

import java.time.LocalDate
import scala.concurrent.ExecutionContext.Implicits.global

class BobbyWarningsNotificationsRepositorySpec
  extends AnyWordSpec
  with Matchers
  with DefaultPlayMongoRepositorySupport[BobbyWarningsNotificationsRunDate] {

  override protected val repository: BobbyWarningsNotificationsRepository = new BobbyWarningsNotificationsRepository(mongoComponent)


  "BobbyWarningsRepository" should {
    "return None for the last run date when the notifications have never been run" in {
      repository.getLastWarningsDate().futureValue shouldBe None
    }
    "insert the current date when updating the last run date" in {
      val runDate = LocalDate.now()
      val result =
        for {
          _ <- repository.setLastRunDate(runDate)
          r <- repository.getLastWarningsDate()
      } yield r
       result.futureValue shouldBe Some(runDate)
    }
  }
}
