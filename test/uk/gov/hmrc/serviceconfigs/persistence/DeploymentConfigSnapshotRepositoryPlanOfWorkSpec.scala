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

package uk.gov.hmrc.serviceconfigs.persistence

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork

import java.time.Instant

class DeploymentConfigSnapshotRepositoryPlanOfWorkSpec extends AnyWordSpec with Matchers {

  "The correct `PlanOfWork`" should {

    "Be produced for the scenario of: a new snapshot being taken of a deployed service" in {

      val latestSnapshots =
        List(someDeploymentConfigSnapshot)

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots = List(someDeploymentConfigSnapshot.copy(date = now, latest = true)),
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions = List.empty
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork
    }

    "Be produced for the scenario of: a service that was decommissioned and then reintroduced" in {
      val latestSnapshots =
        List(someDeploymentConfigSnapshot.copy(deleted = true))

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots = List.empty,
          snapshotServiceReintroductions =
            List(someDeploymentConfigSnapshot.copy(date = now, latest = true)),
          snapshotSynthesisedDeletions = List.empty
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork

    }

    "Be produced for the scenario of: a newly-introduced service" in {

      val latestSnapshots =
        List.empty[DeploymentConfigSnapshot]

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots = List(someDeploymentConfigSnapshot.copy(date = now, latest = true)),
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions = List.empty
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork

    }

    "Be produced for the scenario of: a service being decommissioned" in {

      val latestSnapshots =
        List(someDeploymentConfigSnapshot)

      val currentDeploymentConfigs =
        List.empty[DeploymentConfig]

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots = List.empty,
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions =
            List(
              someDeploymentConfigSnapshot
                .copy(
                  date = now,
                  latest = true,
                  deleted = true,
                  deploymentConfig = someDeploymentConfig.copy(slots = 0, instances = 0)
                )
            )
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork
    }

    "Be produced for the scenario of: a service that was decommissioned and not reintroduced" in {

      val latestSnapshots =
        List(someDeploymentConfigSnapshot.copy(deleted = true))

      val currentDeploymentConfigs =
        List.empty[DeploymentConfig]

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots = List.empty,
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions = List.empty
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork
    }
  }

  private lazy val now =
    Instant.now()

  private lazy val yesterday =
    now.minusSeconds(86400)

  private lazy val someDeploymentConfig =
    DeploymentConfig("A", None, Environment.Production, "", "", 10, 10)

  private lazy val someDeploymentConfigSnapshot =
    DeploymentConfigSnapshot(yesterday, latest = false, deleted = false, someDeploymentConfig)
}
