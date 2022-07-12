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

import org.mockito.scalatest.MockitoSugar
import org.mongodb.scala.ClientSession
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, DefaultPlayMongoRepositorySupport}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepositorySpec._

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeploymentConfigSnapshotRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DeploymentConfigSnapshot]
     with CleanMongoCollectionSupport
     with MockitoSugar
     with IntegrationPatience {

  private val stubDeploymentConfigRepository: DeploymentConfigRepository =
    mock[DeploymentConfigRepository]

  override lazy val repository =
    new DeploymentConfigSnapshotRepository(stubDeploymentConfigRepository, mongoComponent)

  // Test suite elapsed time is significantly reduced with this overridden `PatienceConfig`
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(1000, Millis)), interval = scaled(Span(50, Millis)))

  "DeploymentConfigSnapshotRepository" should {

    "Persist and retrieve `DeploymentConfigSnapshot`s" in {

      (for {
        before <- repository.snapshotsForService("A")
        _      <- repository.add(deploymentConfigSnapshotA1)
        after  <- repository.snapshotsForService("A")
        _       = before shouldBe empty
        _       = after shouldBe List(deploymentConfigSnapshotA1)
      } yield ()).futureValue
    }

    "Retrieve snapshots by service name" in {
      (for {
        _         <- repository.add(deploymentConfigSnapshotA1)
        _         <- repository.add(deploymentConfigSnapshotB1)
        snapshots <- repository.snapshotsForService("A")
        _          = snapshots shouldBe List(deploymentConfigSnapshotA1)
      } yield ()).futureValue
    }

    "Retrieve snapshots sorted by date, ascending" in {

      (for {
        _         <- repository.add(deploymentConfigSnapshotB2)
        _         <- repository.add(deploymentConfigSnapshotB1)
        _         <- repository.add(deploymentConfigSnapshotB3)
        snapshots <- repository.snapshotsForService("B")
          _       = snapshots shouldBe List(deploymentConfigSnapshotB1, deploymentConfigSnapshotB2, deploymentConfigSnapshotB3)
      } yield ()).futureValue

    }

    "Delete all documents in the collection" in  {

      (for {
        _       <- repository.add(deploymentConfigSnapshotA1)
        _       <- repository.add(deploymentConfigSnapshotB1)
        _       <- repository.add(deploymentConfigSnapshotB2)
        _       <- repository.add(deploymentConfigSnapshotB3)
        beforeA <- repository.snapshotsForService("A")
        beforeB <- repository.snapshotsForService("B")
        before   = beforeA ++ beforeB
        _       <- repository.deleteAll()
        afterA  <- repository.snapshotsForService("A")
        afterB  <- repository.snapshotsForService("B")
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
        _         <- withClientSession(repository.removeLatestFlagForServiceInEnvironment("C", Environment.Production, _))
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
        snapshotsA      <- repository.snapshotsForService("A")
        snapshotsD      <- repository.snapshotsForService("D")
        snapshotsE      <- repository.snapshotsForService("E")
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
      DeploymentConfig("A", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotA2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig("A", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotA3: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-03T00:00:00.00Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig("A", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-01T00:00:00.000Z"),
      latest  = false,
      deleted = false,
      DeploymentConfig("B", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest = false,
      deleted = false,
      DeploymentConfig("B", None, Environment.QA, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB3: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-03T00:00:00.000Z"),
      latest = false,
      deleted = false,
      DeploymentConfig("B", None, Environment.Staging, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotC1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-01T00:00:00.000Z"),
      latest  = false,
      deleted = false,
      DeploymentConfig("C", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotC2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = true,
      DeploymentConfig("C", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotD1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = true,
      DeploymentConfig("D", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotD2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-03T00:00:00.000Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig("D", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotE1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-01T00:00:00.000Z"),
      latest  = true,
      deleted = false,
      DeploymentConfig("E", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotE2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      date    = Instant.parse("2021-01-02T00:00:00.000Z"),
      latest  = true,
      deleted = true,
      DeploymentConfig("E", None, Environment.Production, "public", "service", 5, 1),
    )
}
