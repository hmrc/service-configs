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
import uk.gov.hmrc.serviceconfigs.model.{GrantType, InternalAuthConfig, InternalAuthEnvironment, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class InternalAuthConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[InternalAuthConfig] {

  override lazy val repository = new InternalAuthConfigRepository(mongoComponent)

  "InternalAuthConfigRepository" should {
    "put and retrieve" in {
      val config1 = InternalAuthConfig(ServiceName("serviceName1"), InternalAuthEnvironment.Qa,  GrantType.Grantee)
      val config2 = InternalAuthConfig(ServiceName("serviceName2"), InternalAuthEnvironment.Prod, GrantType.Grantor)
      repository.putAll(Set(config1, config2)).futureValue

      repository.findByService(config1.serviceName).futureValue shouldBe Seq(config1)
      repository.findByService(config2.serviceName).futureValue shouldBe Seq(config2)

      repository.putAll(Set(config1.copy(grantType = GrantType.Grantor))).futureValue
      repository.findByService(config1.serviceName).futureValue shouldBe Seq(config1.copy(grantType = GrantType.Grantor))
      repository.findByService(config2.serviceName).futureValue shouldBe Seq.empty
    }
  }
}
