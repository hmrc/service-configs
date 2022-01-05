/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository

import java.time.LocalDateTime
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ResourceUsageServiceSpec extends AnyWordSpec with Matchers with ScalaFutures with MockitoSugar {

  "Service" should {

    "Map `DeploymentConfigSnapshot`s to `ResourceUsage`" in {

      val snapshots =
        Seq(
          DeploymentConfigSnapshot(
            LocalDateTime.of(2021, 1, 1, 0, 0, 0),
            "name",
            Environment.Staging,
            Some(DeploymentConfig("name", None, Environment.Staging, "public", "service", 5, 1)),
          ),
          DeploymentConfigSnapshot(
            LocalDateTime.of(2021, 1, 1, 0, 0, 1),
            "name",
            Environment.Production,
            None
          )
        )

      val resourceUsageService =
        new ResourceUsageService(stubDeploymentConfigSnapshotRepository("name", snapshots))

      val expectedResourceUsages =
        Seq(
          ResourceUsage(
            LocalDateTime.of(2021, 1, 1, 0, 0, 0),
            "name",
            Environment.Staging,
            5,
            1
          ),
          ResourceUsage(
            LocalDateTime.of(2021, 1, 1, 0, 0, 1),
            "name",
            Environment.Production,
            0,
            0
          )
        )

      resourceUsageService.resourceUsageForService("name").futureValue shouldBe expectedResourceUsages
    }
  }

  private def stubDeploymentConfigSnapshotRepository(
    name: String,
    snapshots: Seq[DeploymentConfigSnapshot]
  ): DeploymentConfigSnapshotRepository = {
    val repository =
      mock[DeploymentConfigSnapshotRepository]

    when(repository.snapshotsForService(name)).thenReturn(Future.successful(snapshots))

    repository
  }
}
