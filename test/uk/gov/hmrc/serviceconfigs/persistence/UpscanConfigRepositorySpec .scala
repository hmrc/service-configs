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
import uk.gov.hmrc.serviceconfigs.model.{Environment, UpscanConfig, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class UpscanConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[UpscanConfig] {

  override lazy val repository = new UpscanConfigRepository(mongoComponent)

  "UpscanConfigRepository" should {
    "put and retrieve" in {
      val upscanConfig1 = UpscanConfig(serviceName = ServiceName("testName1"), location = "1", environment = Environment.Production)
      val upscanConfig2 = UpscanConfig(serviceName = ServiceName("testName2"), location = "2", environment = Environment.Production)
      repository.putAll(Seq(upscanConfig1, upscanConfig2)).futureValue

      repository.findByService(upscanConfig1.serviceName).futureValue shouldBe Seq(upscanConfig1)
      repository.findByService(upscanConfig2.serviceName).futureValue shouldBe Seq(upscanConfig2)

      repository.putAll(Seq(upscanConfig1.copy(location = "2"))).futureValue
      repository.findByService(upscanConfig1.serviceName).futureValue shouldBe Seq(upscanConfig1.copy(location = "2"))
      repository.findByService(upscanConfig2.serviceName).futureValue shouldBe empty
    }
  }
}
