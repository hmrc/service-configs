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

import com.mongodb.BasicDBObject
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Sorts}
import org.mongodb.scala.model.Indexes._

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import cats.implicits._
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork.SnapshotServiceReintroduction

@Singleton
class DeploymentConfigSnapshotRepository @Inject()(
  deploymentConfigRepository: DeploymentConfigRepository,
  mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
  extends PlayMongoRepository(
    mongoComponent = mongoComponent,
    collectionName = "deploymentConfigSnapshots",
    domainFormat = DeploymentConfigSnapshot.mongoFormat,
    indexes = Seq(
      IndexModel(hashed("latest"), IndexOptions().background(true)),
      IndexModel(hashed("deploymentConfig.name"), IndexOptions().background(true)),
      IndexModel(hashed("deploymentConfig.environment"), IndexOptions().background(true)))
  ) with Logging {

  def snapshotsForService(serviceName: String): Future[Seq[DeploymentConfigSnapshot]] =
    collection
      .find(equal("deploymentConfig.name", serviceName))
      .sort(Sorts.ascending("date"))
      .toFuture()

  def latestSnapshotsInEnvironment(environment: Environment): Future[Seq[DeploymentConfigSnapshot]] =
    collection
      .find(
        and(
          equal("deploymentConfig.environment", environment.asString),
          equal("latest", true)
        )
      ).toFuture()

  def add(snapshot: DeploymentConfigSnapshot): Future[Unit] =
    collection
      .findOneAndReplace(
        filter = and(
          equal("deploymentConfig.name", snapshot.deploymentConfig.name),
          equal("deploymentConfig.environment", snapshot.deploymentConfig.environment.asString),
          equal("date", snapshot.date)),
        replacement = snapshot,
        options = FindOneAndReplaceOptions().upsert(true))
      .toFutureOption()
      .map(_ => ())

  def deleteAll(): Future[Unit] =
    collection
      .deleteMany(new BasicDBObject())
      .toFuture
      .map(_ => ())

  def removeLatestFlagForNonDeletedSnapshotsInEnvironment(environment: Environment): Future[Unit] =
    collection
      .updateMany(
        filter = and(
          equal("deploymentConfig.environment", environment.asString),
          equal("latest", true),
          equal("deleted", false),
        ),
        update = set("latest", false)
      ).toFuture().map(_ => ())

  def removeLatestFlagForServiceInEnvironment(serviceName: String, environment: Environment): Future[Unit] =
    collection
      .updateMany(
        filter = and(
          equal("deploymentConfig.name", serviceName),
          equal("deploymentConfig.environment", environment.asString),
        ),
        update = set("latest", false)
      ).toFuture().map(_ => ())


  def populate(date: Instant): Future[Unit] = {

    def forEnvironment(environment: Environment): Future[Unit] = {
      for {
        deploymentConfigs <- deploymentConfigRepository.findAll(environment)
        latestSnapshots   <- latestSnapshotsInEnvironment(environment)
        planOfWork        =  PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(latestSnapshots.toList, deploymentConfigs.toList, date)
        _                 <- executePlanOfWork(planOfWork, environment)
      } yield ()
    }

    Environment.values.foldLeftM(())((_, env) => forEnvironment(env))
  }

  def executePlanOfWork(planOfWork: PlanOfWork, environment: Environment): Future[Unit] = {
    logger.debug(s"Processing `DeploymentConfigSnapshot`s for ${environment.asString}")
    val batchInsertions =
      planOfWork.snapshots.map(_.deploymentConfigSnapshot) ++
      planOfWork.snapshotSynthesisedDeletions.map(_.deploymentConfigSnapshot)

    def reintroduceServiceSnapshot(snapshotServiceReintroduction: SnapshotServiceReintroduction) = {
      logger.debug(s"Creating a snapshot for reintroduced service: ${snapshotServiceReintroduction.deploymentConfigSnapshot}")
      for {
        _ <- removeLatestFlagForServiceInEnvironment(
          snapshotServiceReintroduction.deploymentConfigSnapshot.deploymentConfig.name,
          snapshotServiceReintroduction.deploymentConfigSnapshot.deploymentConfig.environment
        )
        _ <- collection.insertOne(snapshotServiceReintroduction.deploymentConfigSnapshot).toFuture()
      } yield ()
    }

    for {
      _ <- if (batchInsertions.nonEmpty) removeLatestFlagForNonDeletedSnapshotsInEnvironment(environment) else Future.unit
      _ <- collection.insertMany(batchInsertions).toFuture()
      _ <- planOfWork.snapshotServiceReintroductions.foldLeftM(())((_, ssr) => reintroduceServiceSnapshot(ssr))
    } yield ()
  }
}

object DeploymentConfigSnapshotRepository {

  import PlanOfWork._

  final case class PlanOfWork(
    snapshots: List[Snapshot],
    snapshotServiceReintroductions: List[SnapshotServiceReintroduction],
    snapshotSynthesisedDeletions: List[SnapshotSynthesisedDeletion]
  )

  object PlanOfWork {

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
    ): PlanOfWork = {
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

      PlanOfWork(snapshots, snapshotServiceReintroductions, snapshotSynthesisedDeletions)
    }
  }
}
