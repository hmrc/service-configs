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
import uk.gov.hmrc.serviceconfigs.model.ServiceName
import uk.gov.hmrc.serviceconfigs.persistence.ServiceManagerConfigRepository.ServiceManagerConfig

import scala.concurrent.ExecutionContext.Implicits.global

class ServiceManagerConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[ServiceManagerConfig] {

  override val repository: ServiceManagerConfigRepository = new ServiceManagerConfigRepository(mongoComponent)

  "ServiceManagerConfigRepository" should {
    "put and retrieve" in {
      val config1 = ServiceManagerConfig(serviceName = ServiceName("testName1"), location = "1")
      val config2 = ServiceManagerConfig(serviceName = ServiceName("testName2"), location = "2")
      repository.putAll(Seq(config1, config2)).futureValue

      repository.findByService(config1.serviceName).futureValue shouldBe Some(config1)
      repository.findByService(config2.serviceName).futureValue shouldBe Some(config2)

      repository.putAll(Seq(config1.copy(location = "2"))).futureValue
      repository.findByService(config1.serviceName).futureValue shouldBe Some(config1.copy(location = "2"))
      repository.findByService(config2.serviceName).futureValue shouldBe None
    }
  }
}
