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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepositorySpec._

import java.time.{Instant, LocalDateTime, ZoneOffset}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeploymentConfigSnapshotRepositorySpec extends AnyWordSpecLike
  with Matchers
  with PlayMongoRepositorySupport[DeploymentConfigSnapshot]
  with CleanMongoCollectionSupport
  with MockitoSugar {

  private var stubDeploymentConfigRepository: DeploymentConfigRepository =
    mock[DeploymentConfigRepository]

  override lazy val repository =
    new DeploymentConfigSnapshotRepository(stubDeploymentConfigRepository, mongoComponent)

  "DeploymentConfigSnapshotRepository" should {

    "Persist and retrieve `DeploymentConfigSnapshot`s" in {

      val before =
        repository.snapshotsForService("A").futureValue

      repository.add(deploymentConfigSnapshotA).futureValue

      val after =
        repository.snapshotsForService("A").futureValue

      before shouldBe empty
      after shouldBe List(deploymentConfigSnapshotA)
    }

    "Retrieve snapshots by service name" in {

      repository.add(deploymentConfigSnapshotA).futureValue
      repository.add(deploymentConfigSnapshotB1).futureValue

      val snapshots =
        repository.snapshotsForService("A").futureValue

      snapshots shouldBe List(deploymentConfigSnapshotA)
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

      repository.add(deploymentConfigSnapshotA).futureValue
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

    "Snapshot all entries in `DeploymentConfigRepository`" in {

      val deploymentConfigsServiceA =
        List(
          DeploymentConfig("A", None, Environment.Development , "", "service", 1, 1),
          DeploymentConfig("A", None, Environment.Integration , "", "service", 1, 1),
          DeploymentConfig("A", None, Environment.QA          , "", "service", 1, 1),
          DeploymentConfig("A", None, Environment.Staging     , "", "service", 1, 1),
          DeploymentConfig("A", None, Environment.ExternalTest, "", "service", 1, 1),
          DeploymentConfig("A", None, Environment.Production  , "", "service", 1, 1)
        )

      val deploymentConfigsServiceB =
        List(
          DeploymentConfig("B", None, Environment.QA          , "", "service", 1, 1),
          DeploymentConfig("B", None, Environment.Production  , "", "service", 1, 1)
        )

      val deploymentConfigsServiceC =
        List(
          DeploymentConfig("C", None, Environment.QA          , "", "service", 1, 1),
          DeploymentConfig("C", None, Environment.Staging     , "", "service", 1, 1),
          DeploymentConfig("C", None, Environment.ExternalTest, "", "service", 1, 1),
          DeploymentConfig("C", None, Environment.Production  , "", "service", 1, 1)
        )

      val allDeploymentConfigs =
        deploymentConfigsServiceA ++ deploymentConfigsServiceB ++ deploymentConfigsServiceC

      configureDeploymentConfigRepositoryStubs(allDeploymentConfigs)

      def getSnapshots() =
        for {
          snapshotsServiceA <- repository.snapshotsForService("A")
          snapshotsServiceB <- repository.snapshotsForService("B")
          snapshotsServiceC <- repository.snapshotsForService("C")
        } yield (snapshotsServiceA ++ snapshotsServiceB ++ snapshotsServiceC)

      val date = Instant.now()

      val snapshotsPrePopulation = getSnapshots().futureValue
      repository.populate(date).futureValue
      val snapshotsPostPopulation = getSnapshots().futureValue

      val expectedSnapshots =
        allDeploymentConfigs
          .map(DeploymentConfigSnapshot(date, _))

      snapshotsPrePopulation shouldBe empty
      snapshotsPostPopulation should contain theSameElementsAs expectedSnapshots
    }
  }

  private def configureDeploymentConfigRepositoryStubs(stubs: List[DeploymentConfig]) = {
    val byEnvironment =
      stubs.groupBy(_.environment)

    byEnvironment.keys.foreach { env =>
      when(stubDeploymentConfigRepository.findAll(env))
        .thenReturn(Future.successful(byEnvironment(env)))
    }
  }

}

object DeploymentConfigSnapshotRepositorySpec {

  val deploymentConfigSnapshotA: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC),
      DeploymentConfig("A", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB1: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 1, 0, 0, 0).toInstant(ZoneOffset.UTC),
      DeploymentConfig("B", None, Environment.Production, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB2: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 2, 0, 0, 0).toInstant(ZoneOffset.UTC),
      DeploymentConfig("B", None, Environment.QA, "public", "service", 5, 1),
    )

  val deploymentConfigSnapshotB3: DeploymentConfigSnapshot =
    DeploymentConfigSnapshot(
      LocalDateTime.of(2021, 1, 3, 0, 0, 0).toInstant(ZoneOffset.UTC),
      DeploymentConfig("B", None, Environment.Staging, "public", "service", 5, 1),
    )
}