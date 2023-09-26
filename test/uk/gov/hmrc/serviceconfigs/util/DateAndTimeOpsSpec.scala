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

import org.scalacheck.Gen
import org.scalactic.anyvals.PosInt
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import uk.gov.hmrc.serviceconfigs.util.DateAndTimeOps._

import java.time.temporal.ChronoUnit
import java.time._


class DateAndTimeOpsSpec
  extends AnyWordSpec
    with Matchers
    with ScalaCheckPropertyChecks {

  implicit override val generatorDrivenConfig =
    PropertyCheckConfiguration(minSuccessful = PosInt(100))

  "A LocalDate" should {
    "be converted to an Instant at the start of the day" in {
      val instant   = Instant.now()
      val localDate = LocalDate.now()

      instant.truncatedTo(ChronoUnit.DAYS) shouldBe localDate.toInstant
    }
  }

  "An evaluation of an Instant" should {
      "return Some(localDate) when in Working hours 9 - 17 Monday to Friday None if not" in {

        val yearDays = for (yearDay <- Gen.choose(1, 366)) yield yearDay
        val years    = for (year <- Gen.choose(2023, 2030)) yield year
        val hours    = for (hour <- Gen.choose(0, 23)) yield hour
        val minutes  = for (minute <- Gen.choose(0, 59)) yield minute


        forAll (yearDays, years, hours, minutes) { (yearDay: Int, year: Int, hour: Int, minute: Int) =>

            val date = LocalDate.ofYearDay(year, yearDay)
            val time = LocalTime.of(hour, minute, 0)

            val dateTime = LocalDateTime.of(date, time)
            val instant = dateTime.atZone(ZoneOffset.UTC).toInstant

            if (date.getDayOfWeek != DayOfWeek.SATURDAY
              && date.getDayOfWeek != DayOfWeek.SUNDAY
              && time.getHour <= 17
              && time.getHour >= 9
            ) {
              instant.maybeWorkingHours() shouldBe Some(instant)
            } else {
              instant.maybeWorkingHours() shouldBe None
            }
          }
      }
    }
}
