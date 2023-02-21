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
import uk.gov.hmrc.serviceconfigs.model.ServiceRelationship

import scala.concurrent.ExecutionContext.Implicits.global

class ServiceRelationshipRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[ServiceRelationship] {

  override lazy val repository = new ServiceRelationshipRepository(mongoComponent)

  private val seed = Seq(
    ServiceRelationship("service-a", "service-b"),
    ServiceRelationship("service-a", "service-c"),
    ServiceRelationship("service-b", "service-a"),
    ServiceRelationship("service-c", "service-b"),
    ServiceRelationship("service-d", "service-a"),
  )

  "ServiceRelationshipsRepository" should {
    "return upstream dependencies for a given service" in {
      repository.putAll(seed).futureValue

      repository.getInboundServices("service-b").futureValue shouldBe List("service-a", "service-c")
    }

    "return downstream dependencies for a given service" in {
      repository.putAll(seed).futureValue

      repository.getOutboundServices("service-a").futureValue shouldBe List("service-b", "service-c")
    }

    "clear the collection and refresh" in {
      repository.putAll(seed).futureValue

      repository.getOutboundServices("service-a").futureValue shouldBe List("service-b", "service-c")

      val latest: Seq[ServiceRelationship] =
        Seq(
          ServiceRelationship("service-a", "service-b"),
          ServiceRelationship("service-a", "service-c"),
          ServiceRelationship("service-a", "service-d"),
          ServiceRelationship("service-b", "service-a"),
          ServiceRelationship("service-c", "service-b"),
          ServiceRelationship("service-d", "service-a"),
        )

      repository.putAll(latest).futureValue

      repository.getOutboundServices("service-a").futureValue shouldBe List("service-b", "service-c", "service-d")
    }
  }

}
