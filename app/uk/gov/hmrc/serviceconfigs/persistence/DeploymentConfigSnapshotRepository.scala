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

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import cats.implicits._
import org.mongodb.scala.ClientSession
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.mongodb.scala.model.{Aggregates, DeleteOneModel, FindOneAndReplaceOptions, Indexes, IndexModel, IndexOptions, Sorts}
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Updates.set
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigSnapshot, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository.PlanOfWork

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.Duration

@Singleton
class DeploymentConfigSnapshotRepository @Inject()(
  deploymentConfigRepository : DeploymentConfigRepository,
  override val mongoComponent: MongoComponent,
  config                     : play.api.Configuration,
  mongoLockRepository        : uk.gov.hmrc.mongo.lock.MongoLockRepository,
)(implicit
  ec: ExecutionContext,
  mat: Materializer
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "deploymentConfigSnapshots",
  domainFormat   = DeploymentConfigSnapshot.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("latest"), IndexOptions().background(true)),
                     IndexModel(Indexes.hashed("deploymentConfig.name"), IndexOptions().background(true)),
                     IndexModel(Indexes.hashed("deploymentConfig.environment"), IndexOptions().background(true)),
                     IndexModel(Indexes.ascending("date"), IndexOptions().expireAfter(7 * 365, TimeUnit.DAYS).background(true))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) with Transactions with Logging {

  private implicit val tc: TransactionConfiguration =
    TransactionConfiguration.strict

  if (config.get[Boolean]("dedupeDeploymentConfigSnapshots")) {
    for {
      taken <- mongoLockRepository.takeLock(lockId = "deploymentConfigSnapshots_migration", owner = "", ttl = config.get[Duration]("deploymentConfigSnapshots_migration.ttl"))
      _     <- if (taken) cleanupDuplicates() else Future.unit
    } yield ()
  }

  def cleanupDuplicates(): Future[Unit] = {
    logger.info(s"Launching migration")
    Source.fromPublisher(
      mongoComponent.database.getCollection("deploymentConfigSnapshots")
        .aggregate(Seq(
          BsonDocument("$group" ->
            BsonDocument(
              "_id" -> BsonDocument("name" -> "$deploymentConfig.name", "environment" -> "$deploymentConfig.environment")
            )
          ),
          BsonDocument("$replaceRoot" -> BsonDocument("newRoot" ->  "$_id")),
          Aggregates.sort(Sorts.ascending("name", "environment"))
        ))
        .allowDiskUse(true)
        .map { bson =>
          for {
            name        <- bson.get[BsonString]("name")
            environment <- bson.get[BsonString]("environment")
          } yield (name.getValue, environment.getValue)
        }
    )
     .collect { case Some(s) => s }
     .flatMapConcat { case (name, environment) =>
       logger.info(s"Cleaning up: $name $environment")
       Source.fromPublisher(
         collection
          .aggregate(Seq(
            Aggregates.`match`(and(equal("deploymentConfig.name", name), equal("deploymentConfig.environment", environment))),
            Aggregates.sort(Sorts.ascending("date"))
          ))
          .allowDiskUse(true)
       ).sliding(2, 1)
        .mapConcat {
          case prev +: current +: _ =>
            if (current.deploymentConfig == prev.deploymentConfig)
              List(
                DeleteOneModel(
                  and(
                    equal("deploymentConfig.name"       , name),
                    equal("deploymentConfig.environment", environment),
                    equal("date"                        , current.date)
                  )
                )
              )
            else {
              logger.info(s"Changed - keep : ${current.date} with ${prev.date}\n${current.deploymentConfig} with ${prev.deploymentConfig}")
              List.empty
            }
          case _ => List.empty
       }
       .grouped(1000)
       .mapAsync(1)(g => collection.bulkWrite(g).toFuture().map(_ => ()) )
       .recover[Unit] { case t: Throwable => logger.error(s"Failed to delete ${t.getMessage}", t) }
    }
    .runWith(Sink.fold(())((_, _) => ()))
    .andThen { t => logger.info(s"Finished migration: $t") }
  }

  def snapshotsForService(serviceName: ServiceName): Future[Seq[DeploymentConfigSnapshot]] =
    collection
      .find(equal("deploymentConfig.name", serviceName))
      .sort(Sorts.ascending("date"))
      .toFuture()

  private[persistence] def latestSnapshotsInEnvironment(environment: Environment): Future[Seq[DeploymentConfigSnapshot]] =
    collection
      .find(
        and(
          equal("deploymentConfig.environment", environment),
          equal("latest", true)
        )
      ).toFuture()

  // for IntegrationTestController
  def add(snapshot: DeploymentConfigSnapshot): Future[Unit] =
    collection
      .findOneAndReplace(
        filter      = and(
                        equal("deploymentConfig.name"       , snapshot.deploymentConfig.serviceName),
                        equal("deploymentConfig.environment", snapshot.deploymentConfig.environment),
                        equal("date"                        , snapshot.date)
                      ),
        replacement = snapshot,
        options     = FindOneAndReplaceOptions().upsert(true))
      .toFutureOption()
      .map(_ => ())

  private[persistence] def removeLatestFlagForNonDeletedSnapshotsInEnvironment(
    environment: Environment,
    session    : ClientSession
  ): Future[Unit] =
    collection
      .updateMany(
        session,
        filter = and(
                   equal("deploymentConfig.environment", environment),
                   equal("latest"                      , true),
                   equal("deleted"                     , false)
                 ),
        update = set("latest", false)
      )
      .toFuture()
      .map(_ =>())

  private[persistence] def removeLatestFlagForServiceInEnvironment(
    serviceName: ServiceName,
    environment: Environment,
    session    : ClientSession
  ): Future[Unit] =
    collection
      .updateMany(
        session,
        filter = and(
                   equal("latest"                      , true),
                   equal("deploymentConfig.name"       , serviceName),
                   equal("deploymentConfig.environment", environment)
                 ),
        update = set("latest", false)
      )
      .toFuture()
      .map(_ =>())

  def populate(date: Instant): Future[Unit] =
    Environment.values.foldLeftM[Future, Unit](())((_, environment) =>
      for {
        deploymentConfigs <- deploymentConfigRepository.findAllForEnv(environment)
        latestSnapshots   <- latestSnapshotsInEnvironment(environment)
        planOfWork        =  PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(latestSnapshots.toList, deploymentConfigs.toList, date)
        _                 <- executePlanOfWork(planOfWork, environment)
      } yield ()
    )

  private[persistence] def executePlanOfWork(planOfWork: PlanOfWork, environment: Environment): Future[Unit] = {
    logger.debug(s"Processing `DeploymentConfigSnapshot`s for ${environment.asString}")

    def bulkInsertSnapshots(snapshots: List[DeploymentConfigSnapshot]) =
      if (snapshots.isEmpty)
        Future.unit
      else
        withSessionAndTransaction { session =>
        for {
          _ <- removeLatestFlagForNonDeletedSnapshotsInEnvironment(environment, session)
          _ <- collection.insertMany(session, snapshots).toFuture()
        } yield ()
      }

    def reintroduceServiceSnapshot(deploymentConfigSnapshot: DeploymentConfigSnapshot) = {
      logger.debug(s"Creating a snapshot for reintroduced service: $deploymentConfigSnapshot")
      withSessionAndTransaction { session =>
        for {
          _ <- removeLatestFlagForServiceInEnvironment(
                 deploymentConfigSnapshot.deploymentConfig.serviceName,
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
          .map(s => (s.deploymentConfig.serviceName, s.deploymentConfig.environment) -> s)
          .toMap

      val planOfWork =
        currentDeploymentConfigs
          .foldLeft(PlanOfWork(List.empty[DeploymentConfigSnapshot], List.empty[DeploymentConfigSnapshot])) {
            case (acc, deploymentConfig) =>
              val deploymentConfigSnapshot =
                DeploymentConfigSnapshot(date, latest = true, deleted = false, deploymentConfig)
              latestSnapshotsByNameAndEnv.get((deploymentConfig.serviceName, deploymentConfig.environment)) match {
                case Some(currentLatest) if currentLatest.deleted =>
                  acc.copy(snapshotServiceReintroductions = deploymentConfigSnapshot +: acc.snapshotServiceReintroductions)
                case Some(currentLatest) if currentLatest.deploymentConfig == deploymentConfig =>
                  // no change, ignore
                  acc
                case _ =>
                  acc.copy(snapshots = deploymentConfigSnapshot +: acc.snapshots)
              }
          }

      val snapshotSynthesisedDeletions = {
        val nameAndEnvForCurrentDeploymentConfigs =
          currentDeploymentConfigs.map(c => (c.serviceName, c.environment))

        (latestSnapshotsByNameAndEnv -- nameAndEnvForCurrentDeploymentConfigs)
          .values
          .filterNot(_.deleted)
          .map(synthesiseDeletedDeploymentConfigSnapshot(_, date))
      }

      planOfWork.copy(snapshots = planOfWork.snapshots ++ snapshotSynthesisedDeletions)
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
