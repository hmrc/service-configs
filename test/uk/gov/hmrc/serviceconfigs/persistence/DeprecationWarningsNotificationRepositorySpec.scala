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
import uk.gov.hmrc.serviceconfigs.persistence.SlackNotificationsRepository.DeprecationWarningsNotificationsRunTime

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext.Implicits.global

class DeprecationWarningsNotificationRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DeprecationWarningsNotificationsRunTime]:

  override protected val repository: DeprecationWarningsNotificationRepository =
    DeprecationWarningsNotificationRepository(mongoComponent)

  "DeprecationWarningsNotificationRepository" should:
    "return None for the last run time when the notifications have never been run" in:
      repository.getLastWarningsRunTime().futureValue shouldBe None

    "insert the current time when updating the last run time" in:
      val runTime = Instant.now().truncatedTo(ChronoUnit.MILLIS)
      val result =
        for
          _ <- repository.setLastRunTime(runTime)
          r <- repository.getLastWarningsRunTime()
        yield r
      result.futureValue shouldBe Some(runTime)
