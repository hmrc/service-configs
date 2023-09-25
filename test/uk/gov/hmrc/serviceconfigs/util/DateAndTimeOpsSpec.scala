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

package uk.gov.hmrc.serviceconfigs.util

import DateAndTimeOps._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, LocalDateTime, Month, ZoneId, ZoneOffset}


class DateAndTimeOpsSpec
  extends AnyWordSpec
    with Matchers {

  "A LocalDate" should {
    "be converted to an Instant at the start of the day" in {
      val instant   = Instant.now()
      val localDate = LocalDate.now()

      instant.truncatedTo(ChronoUnit.DAYS) shouldBe localDate.toInstant
    }
  }

  "Given an Instant.now().isInWorkingHours" when {
    "the time is between 0900 and 1700 and the day is Mon to Fri " should {
      "return Some(Instant.now())" in {
        
        val monday18Sept2020 = LocalDateTime.of(2023, Month.SEPTEMBER, 18, 9, 0)
        val instant = monday18Sept2020.atZone(ZoneOffset.UTC).toInstant

        instant.maybeWorkingHours shouldBe Some(instant)

      }
    }
    "the time is outside 0900 to 1700 and the day is Sat or Sun" should {
      "return false" in {
        val sunday17Sept2020 = LocalDateTime.of(2023, Month.SEPTEMBER, 17, 9, 0)
        val instant = sunday17Sept2020.atZone(ZoneOffset.UTC).toInstant

        instant.maybeWorkingHours shouldBe None
      }
    }
  }

}
