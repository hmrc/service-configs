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
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, Version}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentEventRepository.DeploymentEvent

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

class DeploymentEventRepositorySpec
  extends AnyWordSpec
    with Matchers
    with DefaultPlayMongoRepositorySupport[DeploymentEvent] {

  override lazy val repository = new DeploymentEventRepository(mongoComponent)

  val now: Instant = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)

  val event: DeploymentEvent = DeploymentEventRepository.DeploymentEvent(
    ServiceName("testService"),
    Environment.Development,
    Version("0.1.0"),
    "testDeploymentId",
    configChanged = true,
    "testConfigId",
    now
  )

  "DeploymentEventRepository" should {

    "successfully insert and find a DeploymentEvent" in {
      repository.put(event).futureValue

      val foundEvent = repository.find(event.deploymentId).futureValue

      foundEvent shouldBe Some(event)
    }

    "successfully upsert a DeploymentEvent" in {
      repository.put(event).futureValue

      val updatedEvent = event.copy(configChanged = false)

      repository.put(updatedEvent).futureValue

      val foundEvent = repository.find(event.deploymentId).futureValue

      foundEvent shouldBe Some(updatedEvent)
    }

    "successfully delete a DeploymentEvent" in {
      repository.put(event).futureValue

      repository.delete(event.deploymentId).futureValue

      val foundEvent = repository.find(event.deploymentId).futureValue

      foundEvent shouldBe None
    }
  }
}
