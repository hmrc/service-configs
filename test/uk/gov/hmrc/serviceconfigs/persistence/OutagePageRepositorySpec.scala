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
      val serviceName1 = ServiceName("service-1")
      val environments1 = List(Environment.Development, Environment.QA)
      val serviceName2 = ServiceName("service-2")
      val environments2 = List(Environment.QA, Environment.Production)
      repository.putAll(Seq(
        OutagePage(serviceName1, environments1),
        OutagePage(serviceName2, environments2)
      )).futureValue

      repository.findByServiceName(serviceName1).futureValue shouldBe Some(environments1)
      repository.findByServiceName(serviceName2).futureValue shouldBe Some(environments2)

      val environments3 = List(Environment.Development, Environment.Production)
      repository.putAll(Seq(OutagePage(serviceName1, environments3))).futureValue
      repository.findByServiceName(serviceName1).futureValue shouldBe Some(environments3)
      repository.findByServiceName(serviceName2).futureValue shouldBe None
    }
  }
}
