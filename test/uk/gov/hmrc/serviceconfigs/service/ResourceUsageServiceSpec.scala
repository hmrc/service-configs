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

package uk.gov.hmrc.serviceconfigs.service

import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ResourceUsageServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  "Service" should {

    "Map `DeploymentConfigSnapshot`s to `ResourceUsage`" in {

      val snapshots =
        Seq(
          DeploymentConfigSnapshot(
            date    = Instant.parse("2021-01-01T00:00:00.000Z"),
            latest  = false,
            deleted = false,
            DeploymentConfig(ServiceName("A"), None, Environment.Staging, "public", "service", 5, 1),
          ),
          DeploymentConfigSnapshot(
            date    = Instant.parse("2021-01-01T00:00:00.000Z"),
            latest  = false,
            deleted = false,
            DeploymentConfig(ServiceName("B"), None, Environment.Production, "public", "service", 5, 1),
          )
        )

      val resourceUsageService =
        new ResourceUsageService(stubDeploymentConfigSnapshotRepository(ServiceName("name"), snapshots))

      val expectedHistoricResourceUsages =
        Seq(
          ResourceUsage(
            date        = Instant.parse("2021-01-01T00:00:00.000Z"),
            serviceName = ServiceName("A"),
            environment = Environment.Staging,
            slots       = 5,
            instances   = 1
          ),
          ResourceUsage(
            date        = Instant.parse("2021-01-01T00:00:00.000Z"),
            serviceName = ServiceName("B"),
            environment = Environment.Production,
            slots       = 5,
            instances   = 1
          )
        )

      resourceUsageService.resourceUsageSnapshotsForService(ServiceName("name")).futureValue shouldBe
        expectedHistoricResourceUsages
    }
  }

  private def stubDeploymentConfigSnapshotRepository(
    serviceName: ServiceName,
    snapshots  : Seq[DeploymentConfigSnapshot]
  ): DeploymentConfigSnapshotRepository = {
    val repository =
      mock[DeploymentConfigSnapshotRepository]

    when(repository.snapshotsForService(serviceName))
      .thenReturn(Future.successful(snapshots))

    repository
  }
}
