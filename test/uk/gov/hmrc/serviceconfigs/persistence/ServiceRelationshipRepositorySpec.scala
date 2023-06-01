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
import uk.gov.hmrc.serviceconfigs.model.{ServiceName, ServiceRelationship}

import scala.concurrent.ExecutionContext.Implicits.global

class ServiceRelationshipRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[ServiceRelationship] {

  override lazy val repository = new ServiceRelationshipRepository(mongoComponent)

  private val serviceA = ServiceName("service-a")
  private val serviceB = ServiceName("service-b")
  private val serviceC = ServiceName("service-c")
  private val serviceD = ServiceName("service-d")

  private val seed = Seq(
    ServiceRelationship(serviceA, serviceB),
    ServiceRelationship(serviceA, serviceC),
    ServiceRelationship(serviceB, serviceA),
    ServiceRelationship(serviceC, serviceB),
    ServiceRelationship(serviceD, serviceA)
  )

  "ServiceRelationshipsRepository" should {
    "return upstream dependencies for a given service" in {
      repository.putAll(seed).futureValue

      repository.getInboundServices(serviceB).futureValue shouldBe List(serviceA, serviceC)
    }

    "return downstream dependencies for a given service" in {
      repository.putAll(seed).futureValue

      repository.getOutboundServices(serviceA).futureValue shouldBe List(serviceB, serviceC)
    }

    "clear the collection and refresh" in {
      repository.putAll(seed).futureValue

      repository.getOutboundServices(serviceA).futureValue shouldBe List(serviceB, serviceC)

      val latest: Seq[ServiceRelationship] =
        Seq(
          ServiceRelationship(serviceA, serviceB),
          ServiceRelationship(serviceA, serviceC),
          ServiceRelationship(serviceA, serviceD),
          ServiceRelationship(serviceB, serviceA),
          ServiceRelationship(serviceC, serviceB),
          ServiceRelationship(serviceD, serviceA),
        )

      repository.putAll(latest).futureValue

      repository.getOutboundServices(serviceA).futureValue shouldBe List(serviceB, serviceC, serviceD)
    }
  }

}
