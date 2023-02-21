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

import cats.implicits._
import org.mongodb.scala.ClientSession
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Indexes
import org.mongodb.scala.model.Updates.set
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Sorts}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigSnapshotRepository @Inject()(
  deploymentConfigRepository : DeploymentConfigRepository,
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
)
  extends PlayMongoRepository(
    mongoComponent = mongoComponent,
    collectionName = "deploymentConfigSnapshots",
    domainFormat   = DeploymentConfigSnapshot.mongoFormat,
    indexes        = Seq(
                       IndexModel(Indexes.hashed("latest"), IndexOptions().background(true)),
                       IndexModel(Indexes.hashed("deploymentConfig.name"), IndexOptions().background(true)),
                       IndexModel(Indexes.hashed("deploymentConfig.environment"), IndexOptions().background(true)),
                       IndexModel(Indexes.ascending("date"), IndexOptions().expireAfter(7 * 365, TimeUnit.DAYS).background(true))
                     )
  ) with Transactions with Logging {

  private implicit val tc: TransactionConfiguration =
    TransactionConfiguration.strict

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

  // Test only
  def deleteAll(): Future[Unit] =
    collection
      .deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())

  private [persistence] def removeLatestFlagForNonDeletedSnapshotsInEnvironment(
    environment: Environment,
    session    : ClientSession
  ): Future[Unit] =
    collection
      .updateMany(
        session,
        filter = and(
                   equal("deploymentConfig.environment", environment.asString),
                   equal("latest"                      , true),
                   equal("deleted"                     , false)
                 ),
        update = set("latest", false)
      )
      .toFuture()
      .map(_ =>())

  private [persistence] def removeLatestFlagForServiceInEnvironment(
    serviceName: String,
    environment: Environment,
    session    : ClientSession
  ): Future[Unit] =
    collection
      .updateMany(
        session,
        filter = and(
                   equal("latest"                      , true),
                   equal("deploymentConfig.name"       , serviceName),
                   equal("deploymentConfig.environment", environment.asString)
                 ),
        update = set("latest", false)
      )
      .toFuture()
      .map(_ =>())

  def populate(date: Instant): Future[Unit] = {

    def forEnvironment(environment: Environment): Future[Unit] = {
      for {
        deploymentConfigs <- deploymentConfigRepository.findAllForEnv(environment)
        latestSnapshots   <- latestSnapshotsInEnvironment(environment)
        planOfWork        =  PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(latestSnapshots.toList, deploymentConfigs.toList, date)
        _                 <- executePlanOfWork(planOfWork, environment)
      } yield ()
    }

    Environment.values.foldLeftM(())((_, env) => forEnvironment(env))
  }

  def executePlanOfWork(planOfWork: PlanOfWork, environment: Environment): Future[Unit] = {
    logger.debug(s"Processing `DeploymentConfigSnapshot`s for ${environment.asString}")

    def bulkInsertSnapshots(snapshots: List[DeploymentConfigSnapshot]) =
      withSessionAndTransaction { session =>
        for {
          _ <- if (snapshots.nonEmpty)
                 removeLatestFlagForNonDeletedSnapshotsInEnvironment(environment, session)
               else Future.unit
          _ <- collection.insertMany(session, snapshots).toFuture()
        } yield ()
      }

    def reintroduceServiceSnapshot(deploymentConfigSnapshot: DeploymentConfigSnapshot) = {
      logger.debug(s"Creating a snapshot for reintroduced service: $deploymentConfigSnapshot")
      withSessionAndTransaction { session =>
        for {
          _ <- removeLatestFlagForServiceInEnvironment(
                 deploymentConfigSnapshot.deploymentConfig.name,
                 deploymentConfigSnapshot.deploymentConfig.environment,
                 session
               )
          _ <- collection.insertOne(session, deploymentConfigSnapshot).toFuture()
        } yield ()
      }
    }

    for {
      _ <- bulkInsertSnapshots(planOfWork.snapshots)
      // reintroductions are treated separately to ensure latest flag is removed from previously deleted entries -
      // not covered by above bulk flag removal
      _ <- planOfWork.snapshotServiceReintroductions.foldLeftM(())((_, ssr) => reintroduceServiceSnapshot(ssr))
    } yield ()
  }
}

object DeploymentConfigSnapshotRepository {

  final case class PlanOfWork(
    snapshots                     : List[DeploymentConfigSnapshot],
    snapshotServiceReintroductions: List[DeploymentConfigSnapshot]
  )

  object PlanOfWork {

    def fromLatestSnapshotsAndCurrentDeploymentConfigs(
      latestSnapshots         : List[DeploymentConfigSnapshot],
      currentDeploymentConfigs: List[DeploymentConfig],
      date                    : Instant
    ): PlanOfWork = {
      val latestSnapshotsByNameAndEnv =
        latestSnapshots
          .map(s => (s.deploymentConfig.name, s.deploymentConfig.environment) -> s)
          .toMap

      val (snapshots, snapshotServiceReintroductions) =
        currentDeploymentConfigs
          .foldLeft((List.empty[DeploymentConfigSnapshot], List.empty[DeploymentConfigSnapshot])) {
            case ((snapshots, snapshotServiceReintroductions), deploymentConfig) =>
              val deploymentConfigSnapshot =
                DeploymentConfigSnapshot(date, latest = true, deleted = false, deploymentConfig)
              if (latestSnapshotsByNameAndEnv.get((deploymentConfig.name, deploymentConfig.environment)).exists(_.deleted))
                (snapshots, deploymentConfigSnapshot +: snapshotServiceReintroductions)
              else
                (deploymentConfigSnapshot +: snapshots, snapshotServiceReintroductions)
          }

      val snapshotSynthesisedDeletions = {
        val currentDeploymentConfigsByNameAndEnv =
          currentDeploymentConfigs.map(c => (c.name, c.environment))

        (latestSnapshotsByNameAndEnv -- currentDeploymentConfigsByNameAndEnv)
          .values
          .toList
          .filterNot(_.deleted)
          .map(synthesiseDeletedDeploymentConfigSnapshot(_, date))
      }

      PlanOfWork(snapshots ++ snapshotSynthesisedDeletions, snapshotServiceReintroductions)
    }

    private def synthesiseDeletedDeploymentConfigSnapshot(
      deploymentConfigSnapshot: DeploymentConfigSnapshot,
      date                    : Instant
    ): DeploymentConfigSnapshot =
      deploymentConfigSnapshot.copy(
        date             = date,
        latest           = true,
        deleted          = true,
        deploymentConfig = deploymentConfigSnapshot.deploymentConfig.copy(slots = 0, instances = 0)
      )
  }
}
