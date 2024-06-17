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
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ResourceUsage, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.ResourceUsageRepository.PlanOfWork

import java.time.Instant

class ResourceUsageRepositoryPlanOfWorkSpec extends AnyWordSpec with Matchers:

  "The correct `PlanOfWork`" should:
    "Be produced for the scenario of: a new snapshot being taken of a deployed service" in:
      val currentDeploymentConfig =
        someDeploymentConfig.copy(slots = someDeploymentConfig.slots + 1)

      (someDeploymentConfig.slots != currentDeploymentConfig.slots || someDeploymentConfig.instances != currentDeploymentConfig.instances) shouldBe true

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots                      = List(someResourceUsage.copy(
                                             slots     = currentDeploymentConfig.slots,
                                             instances = currentDeploymentConfig.instances,
                                             date      = now,
                                             latest    = true
                                           )),
          snapshotServiceReintroductions = List.empty
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots          = List(someResourceUsage),
          currentDeploymentConfigs = List(currentDeploymentConfig),
          date                     = now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork

    "Be skipped for a new snapshot with no change" in:
      someResourceUsage.slots     shouldBe someDeploymentConfig.slots
      someResourceUsage.instances shouldBe someDeploymentConfig.instances

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots          = List(someResourceUsage),
          currentDeploymentConfigs = List(someDeploymentConfig),
          date                     = now
        )

      actualPlanOfWork shouldBe PlanOfWork(snapshots = List.empty, snapshotServiceReintroductions = List.empty)

    "Be produced for the scenario of: a service that was decommissioned and then reintroduced" in:
      val latestSnapshots =
        List(someResourceUsage.copy(deleted = true))

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots = List.empty,
          snapshotServiceReintroductions =
            List(someResourceUsage.copy(date = now, latest = true))
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork

    "Be produced for the scenario of: a newly-introduced service" in:
      val latestSnapshots =
        List.empty[ResourceUsage]

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots = List(someResourceUsage.copy(date = now, latest = true)),
          snapshotServiceReintroductions = List.empty
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork

    "Be produced for the scenario of: a service being decommissioned" in:
      val latestSnapshots =
        List(someResourceUsage)

      val currentDeploymentConfigs =
        List.empty[DeploymentConfig]

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots =
            List(
              someResourceUsage
                .copy(
                  date             = now,
                  slots            = 0,
                  instances        = 0,
                  latest           = true,
                  deleted          = true
                )
            ),
            snapshotServiceReintroductions = List.empty,
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork

    "Be produced for the scenario of: a service that was decommissioned and not reintroduced" in:
      val latestSnapshots =
        List(someResourceUsage.copy(deleted = true))

      val currentDeploymentConfigs =
        List.empty[DeploymentConfig]

      val expectedPlanOfWork =
        PlanOfWork(
          snapshots                      = List.empty,
          snapshotServiceReintroductions = List.empty,
        )

      val actualPlanOfWork =
        PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualPlanOfWork shouldBe expectedPlanOfWork

  private lazy val now =
    Instant.now()

  private lazy val yesterday =
    now.minusSeconds(24 * 60 * 60)

  private lazy val someDeploymentConfig =
    DeploymentConfig(ServiceName("A"), None, Environment.Production, "", "", 10, 10, Map.empty, Map.empty, false)

  private lazy val someResourceUsage =
    ResourceUsage(
      date        = yesterday,
      serviceName = someDeploymentConfig.serviceName,
      environment = someDeploymentConfig.environment,
      slots       = someDeploymentConfig.slots,
      instances   = someDeploymentConfig.instances,
      latest      = false,
      deleted     = false
    )
