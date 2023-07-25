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
import uk.gov.hmrc.serviceconfigs.model._

import scala.concurrent.ExecutionContext.Implicits.global

class OutagePageRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[OutagePage] {

  override protected val repository = new OutagePageRepository(mongoComponent)

  "OutagePageRepository" should {
    "putAll correctly" in {
      val serviceName = ServiceName("service-name")
      val environments = List(Environment.Development, Environment.QA)
      repository.putAll(Seq(OutagePage(serviceName, environments))).futureValue

      repository.findByServiceName(serviceName).futureValue shouldBe Some(environments)
    }
  }
}
