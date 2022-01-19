/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliancGe with the License.
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

import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotter.Work._

import java.time.Instant
import javax.inject.{Inject, Singleton}

@Singleton
class DeploymentConfigSnapshotter @Inject()(deploymentConfigSnapshotRepository: DeploymentConfigSnapshotRepository) {

}

object DeploymentConfigSnapshotter {

  final case class Work(
    snapshots: List[Snapshot],
    snapshotServiceReintroductions: List[SnapshotServiceReintroduction],
    snapshotSynthesisedDeletions: List[SnapshotSynthesisedDeletion]
  )

  object Work {

    final case class Snapshot(deploymentConfigSnapshot: DeploymentConfigSnapshot)

    final case class SnapshotSynthesisedDeletion(deploymentConfigSnapshot: DeploymentConfigSnapshot)

    object SnapshotSynthesisedDeletion {

      def fromDeploymentConfigSnapshot(
        deploymentConfigSnapshot: DeploymentConfigSnapshot,
        date: Instant
      ): SnapshotSynthesisedDeletion =
        SnapshotSynthesisedDeletion(
          deploymentConfigSnapshot.copy(
            date = date,
            latest = true,
            deleted = true,
            deploymentConfig = deploymentConfigSnapshot.deploymentConfig.copy(slots = 0, instances = 0)
          )
        )
    }

    final case class SnapshotServiceReintroduction(deploymentConfigSnapshot: DeploymentConfigSnapshot)

    def fromLatestSnapshotsAndCurrentDeploymentConfigs(
      latestSnapshots: List[DeploymentConfigSnapshot],
      currentDeploymentConfigs: List[DeploymentConfig],
      date: Instant
    ): Work = {
      val latestSnapshotsByNameAndEnv =
        latestSnapshots
          .map(s => (s.deploymentConfig.name, s.deploymentConfig.environment) -> s)
          .toMap

      val (snapshots, snapshotServiceReintroductions) =
        currentDeploymentConfigs
          .foldLeft((List.empty[Snapshot], List.empty[SnapshotServiceReintroduction])) {
            case ((snapshots, snapshotServiceReintroductions), deploymentConfig) => {
              val deploymentConfigSnapshot =
                DeploymentConfigSnapshot(date, latest = true, deleted = false, deploymentConfig)
              if (latestSnapshotsByNameAndEnv.get((deploymentConfig.name, deploymentConfig.environment)).exists(_.deleted))
                (snapshots, SnapshotServiceReintroduction(deploymentConfigSnapshot) +: snapshotServiceReintroductions)
              else
                (Snapshot(deploymentConfigSnapshot) +: snapshots, snapshotServiceReintroductions)
            }
          }

      val snapshotSynthesisedDeletions = {
        val currentDeploymentConfigsByNameAndEnv =
          currentDeploymentConfigs.map(c => (c.name, c.environment))

        (latestSnapshotsByNameAndEnv -- currentDeploymentConfigsByNameAndEnv)
          .values
          .toList
          .filterNot(_.deleted)
          .map(SnapshotSynthesisedDeletion.fromDeploymentConfigSnapshot(_, date))
      }

      Work(snapshots, snapshotServiceReintroductions, snapshotSynthesisedDeletions)
    }
  }
}


