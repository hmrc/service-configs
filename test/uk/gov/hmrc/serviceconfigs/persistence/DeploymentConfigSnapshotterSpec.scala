package uk.gov.hmrc.serviceconfigs.persistence

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import DeploymentConfigSnapshotter.Work
import DeploymentConfigSnapshotter.Work._
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}

import java.time.Instant

class DeploymentConfigSnapshotterSpec extends AnyWordSpec with Matchers {

  "The correct plan of `Work`" should {

    "Be produced for the scenario of: a new snapshot being taken of a deployed service" in {

      val latestSnapshots =
        List(someDeploymentConfigSnapshot)

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedWork =
        Work(
          snapshots = List(Snapshot(someDeploymentConfigSnapshot.copy(date = now, latest = true))),
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions = List.empty
        )

      val actualWork =
        Work.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualWork shouldBe expectedWork
    }

    "Be produced for the scenario of: a service that was decommissioned and then reintroduced" in {
      val latestSnapshots =
        List(someDeploymentConfigSnapshot.copy(deleted = true))

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedWork =
        Work(
          snapshots = List.empty,
          snapshotServiceReintroductions =
            List(SnapshotServiceReintroduction(someDeploymentConfigSnapshot.copy(date = now, latest = true))),
          snapshotSynthesisedDeletions = List.empty
        )

      val actualWork =
        Work.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualWork shouldBe expectedWork

    }

    "Be produced for the scenario of: a newly-introduced service" in {

      val latestSnapshots =
        List.empty[DeploymentConfigSnapshot]

      val currentDeploymentConfigs =
        List(someDeploymentConfig)

      val expectedWork =
        Work(
          snapshots = List(Snapshot(someDeploymentConfigSnapshot.copy(date = now, latest = true))),
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions = List.empty
        )

      val actualWork =
        Work.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualWork shouldBe expectedWork

    }

    "Be produced for the scenario of: a service being decommissioned" in {

      val latestSnapshots =
        List(someDeploymentConfigSnapshot)

      val currentDeploymentConfigs =
        List.empty[DeploymentConfig]

      val expectedWork =
        Work(
          snapshots = List.empty,
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions =
            List(
              SnapshotSynthesisedDeletion(
                someDeploymentConfigSnapshot
                  .copy(
                    date = now,
                    latest = true,
                    deleted = true,
                    deploymentConfig = someDeploymentConfig.copy(slots = 0, instances = 0)
                  )
              )
            )
        )

      val actualWork =
        Work.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualWork shouldBe expectedWork
    }

    "Be produced for the scenario of: a service that was decommissioned and not reintroduced" in {

      val latestSnapshots =
        List(someDeploymentConfigSnapshot.copy(deleted = true))

      val currentDeploymentConfigs =
        List.empty[DeploymentConfig]

      val expectedWork =
        Work(
          snapshots = List.empty,
          snapshotServiceReintroductions = List.empty,
          snapshotSynthesisedDeletions = List.empty
        )

      val actualWork =
        Work.fromLatestSnapshotsAndCurrentDeploymentConfigs(
          latestSnapshots,
          currentDeploymentConfigs,
          now
        )

      actualWork shouldBe expectedWork
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
