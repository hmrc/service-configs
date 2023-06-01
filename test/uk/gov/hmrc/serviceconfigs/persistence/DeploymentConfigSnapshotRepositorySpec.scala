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

import org.mockito.scalatest.MockitoSugar
import org.mongodb.scala.ClientSession
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepositorySpec._

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeploymentConfigSnapshotRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DeploymentConfigSnapshot]
     with MockitoSugar {

  private val stubDeploymentConfigRepository: DeploymentConfigRepository =
    mock[DeploymentConfigRepository]

  override lazy val repository =
    new DeploymentConfigSnapshotRepository(stubDeploymentConfigRepository, mongoComponent)

  "DeploymentConfigSnapshotRepository" should {

    "Persist and retrieve `DeploymentConfigSnapshot`s" in {
      (for {
        before <- repository.snapshotsForService(ServiceName("A"))
        _      <- repository.add(deploymentConfigSnapshotA1)
        after  <- repository.snapshotsForService(ServiceName("A"))
        _       = before shouldBe empty
        _       = after shouldBe List(deploymentConfigSnapshotA1)
      } yield ()).futureValue
    }

    "Retrieve snapshots by service name" in {
      (for {
        _         <- repository.add(deploymentConfigSnapshotA1)
        _         <- repository.add(deploymentConfigSnapshotB1)
        snapshots <- repository.snapshotsForService(ServiceName("A"))
        _          = snapshots shouldBe List(deploymentConfigSnapshotA1)
      } yield ()).futureValue
    }

    "Retrieve snapshots sorted by date, ascending" in {
      (for {
        _         <- repository.add(deploymentConfigSnapshotB2)
        _         <- repository.add(deploymentConfigSnapshotB1)
        _         <- repository.add(deploymentConfigSnapshotB3)
        snapshots <- repository.snapshotsForService(ServiceName("B"))
          _       = snapshots shouldBe List(deploymentConfigSnapshotB1, deploymentConfigSnapshotB2, deploymentConfigSnapshotB3)
      } yield ()).futureValue

    }

    "Delete all documents in the collection" in  {
      (for {
        _       <- repository.add(deploymentConfigSnapshotA1)
        _       <- repository.add(deploymentConfigSnapshotB1)
        _       <- repository.add(deploymentConfigSnapshotB2)
        _       <- repository.add(deploymentConfigSnapshotB3)
        beforeA <- repository.snapshotsForService(ServiceName("A"))
        beforeB <- repository.snapshotsForService(ServiceName("B"))
        before   = beforeA ++ beforeB
        _       <- deleteAll()
        afterA  <- repository.snapshotsForService(ServiceName("A"))
        afterB  <- repository.snapshotsForService(ServiceName("B"))
        after    = afterA ++ afterB
        _        = before.size shouldBe 4
        _        = after shouldBe empty
      } yield ()).futureValue
    }

    "Retrieve only the latest snapshots in an environment" in {
      (for {
        _         <- repository.add(deploymentConfigSnapshotA1)
        _         <- repository.add(deploymentConfigSnapshotA2)
        _         <- repository.add(deploymentConfigSnapshotC1)
        _         <- repository.add(deploymentConfigSnapshotC2)
        snapshots <- repository.latestSnapshotsInEnvironment(Environment.Production)
          _        = snapshots shouldBe List( deploymentConfigSnapshotA2, deploymentConfigSnapshotC2 )
      } yield ()).futureValue
    }

    "Remove the `latest` flag for all non-deleted snapshots in an environment" in {
      (for {
        _         <- repository.add(deploymentConfigSnapshotA1)
        _         <- repository.add(deploymentConfigSnapshotA2)
        _         <- repository.add(deploymentConfigSnapshotC1)
        _         <- repository.add(deploymentConfigSnapshotC2)
        _         <- withClientSession(repository.removeLatestFlagForNonDeletedSnapshotsInEnvironment(Environment.Production, _))
        snapshots <- repository.latestSnapshotsInEnvironment(Environment.Production)
        _          = snapshots shouldBe List(deploymentConfigSnapshotC2)
      } yield ()).futureValue
    }

    "Remove the `latest` flag for all snapshots of a service in an environment" in {
      (for {
        _         <- repository.add(deploymentConfigSnapshotC1)
        _         <- repository.add(deploymentConfigSnapshotC2)
        _         <- withClientSession(repository.removeLatestFlagForServiceInEnvironment(ServiceName("C"), Environment.Production, _))
        snapshots <- repository.latestSnapshotsInEnvironment(Environment.Production)
        _          = snapshots shouldBe empty
      } yield ()).futureValue
    }

    "Execute a `PlanOfWork`" in {
      val planOfWork =
        PlanOfWork(
          snapshots = List(deploymentConfigSnapshotA3, deploymentConfigSnapshotE2),
          snapshotServiceReintroductions = List(deploymentConfigSnapshotD2)
        )

      val expectedSnapshots =
        List(
          deploymentConfigSnapshotA2.copy(latest = false),
          deploymentConfigSnapshotA3,
          deploymentConfigSnapshotD1.copy(latest = false),
          deploymentConfigSnapshotD2,
          deploymentConfigSnapshotE1.copy(latest = false),
          deploymentConfigSnapshotE2
        )

      (for {
        _               <- repository.add(deploymentConfigSnapshotA2)
        _               <- repository.add(deploymentConfigSnapshotD1)
        _               <- repository.add(deploymentConfigSnapshotE1)
        _               <- repository.executePlanOfWork(planOfWork, Environment.Production)
        snapshotsA      <- repository.snapshotsForService(ServiceName("A"))
        snapshotsD      <- repository.snapshotsForService(ServiceName("D"))
        snapshotsE      <- repository.snapshotsForService(ServiceName("E"))
        actualSnapshots = snapshotsA ++ snapshotsD ++ snapshotsE
        _               = actualSnapshots should contain theSameElementsAs(expectedSnapshots)
      } yield ()).futureValue
    }
  }

  private def withClientSession[A](f: ClientSession => Future[A]): Future[A] =
    for {
      session <- mongoComponent.client.startSession().toFuture()
      f2      =  f(session)
      _       =  f2.onComplete(_ => session.close())
      res     <- f2
    } yield res
}

object DeploymentConfigSnapshotRepositorySpec {

  val deploymentConfigSnapshotA1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-01T00:00:00.000Z"),
      latest  = false,
      deleted = false,
      DeploymentConfig(ServiceName("A"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotA2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig(ServiceName("A"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotA3: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-03T00:00:00.00Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig(ServiceName("A"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-01T00:00:00.000Z"),
      latest  = false,
      deleted = false,
      DeploymentConfig(ServiceName("B"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest = false,
      deleted = false,
      DeploymentConfig(ServiceName("B"), None, Environment.QA, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB3: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-03T00:00:00.000Z"),
      latest = false,
      deleted = false,
      DeploymentConfig(ServiceName("B"), None, Environment.Staging, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotC1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-01T00:00:00.000Z"),
      latest  = false,
      deleted = false,
      DeploymentConfig(ServiceName("C"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotC2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = true,
      DeploymentConfig(ServiceName("C"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotD1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = true,
      DeploymentConfig(ServiceName("D"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotD2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-03T00:00:00.000Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig(ServiceName("D"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotE1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-01T00:00:00.000Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig(ServiceName("E"), None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotE2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = true,
      DeploymentConfig(ServiceName("E"), None, Environment.Production, "public", "service", 5, 1),
    )
}
