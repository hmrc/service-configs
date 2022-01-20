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
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, DefaultPlayMongoRepositorySupport}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepositorySpec._

import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeploymentConfigSnapshotRepositorySpec extends AnyWordSpecLike
  with Matchers
  with DefaultPlayMongoRepositorySupport[DeploymentConfigSnapshot]
  with CleanMongoCollectionSupport
  with MockitoSugar {

  private val stubDeploymentConfigRepository: DeploymentConfigRepository =
    mock[DeploymentConfigRepository]

  override lazy val repository =
    new DeploymentConfigSnapshotRepository(stubDeploymentConfigRepository, mongoComponent)

  // Test suite elapsed time is significantly reduced with this overridden `PatienceConfig`
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(500, Millis)), interval = scaled(Span(20, Millis)))

  "DeploymentConfigSnapshotRepository" should {

    "Persist and retrieve `DeploymentConfigSnapshot`s" in {

      val before =
        repository.snapshotsForService("A").futureValue

      repository.add(deploymentConfigSnapshotA1).futureValue

      val after =
        repository.snapshotsForService("A").futureValue

      before shouldBe empty
      after shouldBe List(deploymentConfigSnapshotA1)
    }

    "Retrieve snapshots by service name" in {

      repository.add(deploymentConfigSnapshotA1).futureValue
      repository.add(deploymentConfigSnapshotB1).futureValue

      val snapshots =
        repository.snapshotsForService("A").futureValue

      snapshots shouldBe List(deploymentConfigSnapshotA1)
    }

    "Retrieve snapshots sorted by date, ascending" in {

      repository.add(deploymentConfigSnapshotB2).futureValue
      repository.add(deploymentConfigSnapshotB1).futureValue
      repository.add(deploymentConfigSnapshotB3).futureValue

      val snapshots =
        repository.snapshotsForService("B").futureValue

      snapshots shouldBe List(
        deploymentConfigSnapshotB1,
        deploymentConfigSnapshotB2,
        deploymentConfigSnapshotB3
      )
    }

    "Delete all documents in the collection" in  {

      repository.add(deploymentConfigSnapshotA1).futureValue
      repository.add(deploymentConfigSnapshotB1).futureValue
      repository.add(deploymentConfigSnapshotB2).futureValue
      repository.add(deploymentConfigSnapshotB3).futureValue

      val before =
        repository.snapshotsForService("A").futureValue ++
          repository.snapshotsForService("B").futureValue

      repository.deleteAll().futureValue

      val after =
        repository.snapshotsForService("A").futureValue

      before.size shouldBe 4
      after shouldBe empty
    }

    "Retrieve only the latest snapshots in an environment" in {
      repository.add(deploymentConfigSnapshotA1).futureValue
      repository.add(deploymentConfigSnapshotA2).futureValue
      repository.add(deploymentConfigSnapshotC1).futureValue
      repository.add(deploymentConfigSnapshotC2).futureValue

      val snapshots =
        repository.latestSnapshotsInEnvironment(Environment.Production).futureValue

      snapshots shouldBe List(
        deploymentConfigSnapshotA2,
        deploymentConfigSnapshotC2
      )
    }

    "Remove the `latest` flag for all non-deleted snapshots in an environment" in {
      repository.add(deploymentConfigSnapshotA1).futureValue
      repository.add(deploymentConfigSnapshotA2).futureValue
      repository.add(deploymentConfigSnapshotC1).futureValue
      repository.add(deploymentConfigSnapshotC2).futureValue

      withClientSession(repository.removeLatestFlagForNonDeletedSnapshotsInEnvironment(Environment.Production, _))
        .futureValue


      val snapshots =
        repository.latestSnapshotsInEnvironment(Environment.Production).futureValue

      snapshots shouldBe List(deploymentConfigSnapshotC2)
    }

    "Remove the `latest` flag for all snapshots of a service in an environment" in {
      repository.add(deploymentConfigSnapshotC1).futureValue
      repository.add(deploymentConfigSnapshotC2).futureValue

      withClientSession(repository.removeLatestFlagForServiceInEnvironment("C", Environment.Production, _))
        .futureValue

      val snapshots =
        repository.latestSnapshotsInEnvironment(Environment.Production).futureValue

      snapshots shouldBe empty
    }

    "Execute a `PlanOfWork`" in {

      val planOfWork =
        PlanOfWork(
          snapshots = List(deploymentConfigSnapshotA3, deploymentConfigSnapshotE2),
          snapshotServiceReintroductions = List(deploymentConfigSnapshotD2)
        )

      repository.add(deploymentConfigSnapshotA2).futureValue
      repository.add(deploymentConfigSnapshotD1).futureValue
      repository.add(deploymentConfigSnapshotE1).futureValue

      repository.executePlanOfWork(planOfWork, Environment.Production).futureValue

      val expectedSnapshots =
        List(
          deploymentConfigSnapshotA2.copy(latest = false),
          deploymentConfigSnapshotA3,
          deploymentConfigSnapshotD1.copy(latest = false),
          deploymentConfigSnapshotD2,
          deploymentConfigSnapshotE1.copy(latest = false),
          deploymentConfigSnapshotE2
        )

      val actualSnapshots =
        repository.snapshotsForService("A").futureValue ++
        repository.snapshotsForService("D").futureValue ++
        repository.snapshotsForService("E").futureValue

      actualSnapshots should contain theSameElementsAs(expectedSnapshots)
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
      LocalDateTime.of(2021, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = false,
      deleted = false,
      DeploymentConfig("A", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotA2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 2, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = true,
      deleted = false,
      DeploymentConfig("A", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotA3: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 3, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = true,
      deleted = false,
      DeploymentConfig("A", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = false,
      deleted = false,
      DeploymentConfig("B", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 2, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = false,
      deleted = false,
      DeploymentConfig("B", None, Environment.QA, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB3: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 3, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = false,
      deleted = false,
      DeploymentConfig("B", None, Environment.Staging, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotC1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = false,
      deleted = false,
      DeploymentConfig("C", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotC2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 2, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = true,
      deleted = true,
      DeploymentConfig("C", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotD1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 2, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = true,
      deleted = true,
      DeploymentConfig("D", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotD2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 3, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = true,
      deleted = false,
      DeploymentConfig("D", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotE1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = true,
      deleted = false,
      DeploymentConfig("E", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotE2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 2, 0, 0, 0).toInstant(ZoneOffset.UTC),
      latest = true,
      deleted = true,
      DeploymentConfig("E", None, Environment.Production, "public", "service", 5, 1),
    )
}