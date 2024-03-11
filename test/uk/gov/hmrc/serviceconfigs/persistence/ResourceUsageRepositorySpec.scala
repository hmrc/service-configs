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

import org.apache.pekko.actor.ActorSystem
import org.mockito.scalatest.MockitoSugar
import org.mongodb.scala.ClientSession
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{Environment, ResourceUsage, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.ResourceUsageRepository.PlanOfWork
import uk.gov.hmrc.serviceconfigs.persistence.ResourceUsageRepositorySpec._

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ResourceUsageRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[ResourceUsage]
     with MockitoSugar {

  private val mockedDeploymentConfigRepository: DeploymentConfigRepository =
    mock[DeploymentConfigRepository]

  private val as = ActorSystem()

  override lazy val repository =
    new ResourceUsageRepository(mockedDeploymentConfigRepository, mongoComponent, as)

  "ResourceUsageRepository" should {
    "Persist and retrieve `ResourceUsage`s" in {
      (for {
         before <- repository.find(ServiceName("A"))
         _      <- repository.add(resourceUsageA1)
         after  <- repository.find(ServiceName("A"))
         _       = before shouldBe empty
         _       = after shouldBe List(resourceUsageA1)
       } yield ()
      ).futureValue
    }

    "Retrieve snapshots by service name" in {
      (for {
         _         <- repository.add(resourceUsageA1)
         _         <- repository.add(resourceUsageB1)
         snapshots <- repository.find(ServiceName("A"))
         _          = snapshots shouldBe List(resourceUsageA1)
       } yield ()
      ).futureValue
    }

    "Retrieve snapshots sorted by date, ascending" in {
      (for {
         _         <- repository.add(resourceUsageB2)
         _         <- repository.add(resourceUsageB1)
         _         <- repository.add(resourceUsageB3)
         snapshots <- repository.find(ServiceName("B"))
           _       = snapshots shouldBe List(resourceUsageB1, resourceUsageB2, resourceUsageB3)
       } yield ()
      ).futureValue
    }

    "Delete all documents in the collection" in  {
      (for {
         _       <- repository.add(resourceUsageA1)
         _       <- repository.add(resourceUsageB1)
         _       <- repository.add(resourceUsageB2)
         _       <- repository.add(resourceUsageB3)
         beforeA <- repository.find(ServiceName("A"))
         beforeB <- repository.find(ServiceName("B"))
         before   = beforeA ++ beforeB
         _       <- deleteAll()
         afterA  <- repository.find(ServiceName("A"))
         afterB  <- repository.find(ServiceName("B"))
         after    = afterA ++ afterB
         _        = before.size shouldBe 4
         _        = after shouldBe empty
       } yield ()
      ).futureValue
    }

    "Retrieve only the latest snapshots in an environment" in {
      (for {
         _         <- repository.add(resourceUsageA1)
         _         <- repository.add(resourceUsageA2)
         _         <- repository.add(resourceUsageC1)
         _         <- repository.add(resourceUsageC2)
         snapshots <- repository.latestSnapshotsInEnvironment(Environment.Production)
           _        = snapshots shouldBe List( resourceUsageA2, resourceUsageC2 )
       } yield ()
      ).futureValue
    }

    "Remove the `latest` flag for all non-deleted snapshots in an environment" in {
      (for {
         _         <- repository.add(resourceUsageA1)
         _         <- repository.add(resourceUsageA2)
         _         <- repository.add(resourceUsageC1)
         _         <- repository.add(resourceUsageC2)
         _         <- withClientSession(repository.removeLatestFlagForNonDeletedSnapshotsInEnvironment(Environment.Production, _))
         snapshots <- repository.latestSnapshotsInEnvironment(Environment.Production)
         _          = snapshots shouldBe List(resourceUsageC2)
       } yield ()
      ).futureValue
    }

    "Remove the `latest` flag for all snapshots of a service in an environment" in {
      (for {
         _         <- repository.add(resourceUsageC1)
         _         <- repository.add(resourceUsageC2)
         _         <- withClientSession(repository.removeLatestFlagForServiceInEnvironment(ServiceName("C"), Environment.Production, _))
         snapshots <- repository.latestSnapshotsInEnvironment(Environment.Production)
         _          = snapshots shouldBe empty
       } yield ()
      ).futureValue
    }

    "Execute a `PlanOfWork`" in {
      val planOfWork =
        PlanOfWork(
          snapshots = List(resourceUsageA3, resourceUsageE2),
          snapshotServiceReintroductions = List(resourceUsageD2)
        )

      val expectedSnapshots =
        List(
          resourceUsageA2.copy(latest = false),
          resourceUsageA3,
          resourceUsageD1.copy(latest = false),
          resourceUsageD2,
          resourceUsageE1.copy(latest = false),
          resourceUsageE2
        )

      (for {
         _               <- repository.add(resourceUsageA2)
         _               <- repository.add(resourceUsageD1)
         _               <- repository.add(resourceUsageE1)
         _               <- repository.executePlanOfWork(planOfWork, Environment.Production)
         snapshotsA      <- repository.find(ServiceName("A"))
         snapshotsD      <- repository.find(ServiceName("D"))
         snapshotsE      <- repository.find(ServiceName("E"))
         actualSnapshots = snapshotsA ++ snapshotsD ++ snapshotsE
         _               = actualSnapshots should contain theSameElementsAs(expectedSnapshots)
       } yield ()
      ).futureValue
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

object ResourceUsageRepositorySpec {

  val resourceUsageA1: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-01T00:00:00.000Z"),
      serviceName = ServiceName("A"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = false,
      deleted     = false
    )

  val resourceUsageA2: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-02T00:00:00.000Z"),
      serviceName = ServiceName("A"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = true,
      deleted     = false
    )

  val resourceUsageA3: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-03T00:00:00.00Z"),
      serviceName = ServiceName("A"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = true,
      deleted     = false
    )

  val resourceUsageB1: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-01T00:00:00.000Z"),
      serviceName = ServiceName("B"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = false,
      deleted     = false
    )

  val resourceUsageB2: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-02T00:00:00.000Z"),
      serviceName = ServiceName("B"),
      environment = Environment.QA,
      slots       = 5,
      instances   = 1,
      latest      = false,
      deleted     = false
    )

  val resourceUsageB3: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-03T00:00:00.000Z"),
      serviceName = ServiceName("B"),
      environment = Environment.Staging,
      slots       = 5,
      instances   = 1,
      latest      = false,
      deleted     = false
    )

  val resourceUsageC1: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-01T00:00:00.000Z"),
      serviceName = ServiceName("C"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = false,
      deleted     = false,
    )

  val resourceUsageC2: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-02T00:00:00.000Z"),
      serviceName = ServiceName("C"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = true,
      deleted     = true
    )

  val resourceUsageD1: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-02T00:00:00.000Z"),
      serviceName = ServiceName("D"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = true,
      deleted     = true
    )

  val resourceUsageD2: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-03T00:00:00.000Z"),
      serviceName = ServiceName("D"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = true,
      deleted     = false
    )

  val resourceUsageE1: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-01T00:00:00.000Z"),
      serviceName = ServiceName("E"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = true,
      deleted     = false
    )

  val resourceUsageE2: ResourceUsage =
    ResourceUsage(
      date        = Instant.parse("2021-01-02T00:00:00.000Z"),
      serviceName = ServiceName("E"),
      environment = Environment.Production,
      slots       = 5,
      instances   = 1,
      latest      = true,
      deleted     = true
    )
}
