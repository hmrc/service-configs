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

    "Provide a cost estimation based on resource usage" in {
      ResourceUsage(
        LocalDateTime.of(2021, 1, 1, 0, 0, 0),
        "name",
        Environment.Staging,
        slots = 0,
        instances = 1
      ).estimatedYearlyCostUsd shouldBe 120.0

      ResourceUsage(
        LocalDateTime.of(2021, 1, 1, 0, 0, 0),
        "name",
        Environment.Staging,
        slots = 1,
        instances = 1
      ).estimatedYearlyCostUsd shouldBe 120.0

      ResourceUsage(
        LocalDateTime.of(2021, 1, 1, 0, 0, 0),
        "name",
        Environment.Staging,
        slots = 1,
        instances = 0
      ).estimatedYearlyCostUsd shouldBe 0

      ResourceUsage(
        LocalDateTime.of(2021, 1, 1, 0, 0, 0),
        "name",
        Environment.Staging,
        slots = 50,
        instances = 10
      ).estimatedYearlyCostUsd shouldBe 1200.0
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
