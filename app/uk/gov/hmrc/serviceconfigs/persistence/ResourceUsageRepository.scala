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
import org.mongodb.scala.{ClientSession, ClientSessionOptions, ObservableFuture, ReadConcern, ReadPreference, SingleObservableFuture, TransactionOptions, WriteConcern}
import org.mongodb.scala.model.{FindOneAndReplaceOptions, Indexes, IndexModel, IndexOptions, Sorts}
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Updates.set
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ResourceUsage, ServiceName}

import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ResourceUsageRepository @Inject()(
  deploymentConfigRepository : DeploymentConfigRepository,
  override val mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[ResourceUsage](
  mongoComponent = mongoComponent,
  collectionName = "resourceUsage",
  domainFormat   = ResourceUsage.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("latest"), IndexOptions().background(true)),
                     IndexModel(Indexes.hashed("serviceName"), IndexOptions().background(true)),
                     IndexModel(Indexes.hashed("environment"), IndexOptions().background(true)),
                     IndexModel(Indexes.ascending("date"), IndexOptions().expireAfter(7 * 365, TimeUnit.DAYS).background(true))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) with Transactions
  with Logging:
  import ResourceUsageRepository._

  private given TransactionConfiguration =
    TransactionConfiguration(
      clientSessionOptions = Some(
                               ClientSessionOptions.builder()
                                 .causallyConsistent(true)
                                 .build()
                             ),
      transactionOptions   = Some(
                               TransactionOptions.builder()
                                 .readConcern(ReadConcern.MAJORITY)
                                 .writeConcern(WriteConcern.MAJORITY)
                                 .readPreference(ReadPreference.primary())
                                 .build()
                             )
    )

  def find(serviceName: ServiceName): Future[Seq[ResourceUsage]] =
    collection
      .find(equal("serviceName", serviceName))
      .sort(Sorts.ascending("date"))
      .toFuture()

  private[persistence] def latestSnapshotsInEnvironment(environment: Environment): Future[Seq[ResourceUsage]] =
    collection
      .find(
        and(
          equal("environment", environment),
          equal("latest", true)
        )
      ).toFuture()

  // for IntegrationTestController
  def add(snapshot: ResourceUsage): Future[Unit] =
    collection
      .findOneAndReplace(
        filter      = and(
                        equal("serviceName", snapshot.serviceName),
                        equal("environment", snapshot.environment),
                        equal("date"       , snapshot.date)
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
                   equal("environment", environment),
                   equal("latest"     , true),
                   equal("deleted"    , false)
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
                   equal("latest"     , true),
                   equal("serviceName", serviceName),
                   equal("environment", environment)
                 ),
        update = set("latest", false)
      )
      .toFuture()
      .map(_ =>())

  def populate(date: Instant): Future[Unit] =
    Environment.values.toList.foldLeftM[Future, Unit](()):
      (_, environment) =>
        for
          deploymentConfigs <- deploymentConfigRepository.find(applied = true, environments = Seq(environment))
          latestSnapshots   <- latestSnapshotsInEnvironment(environment)
          planOfWork        =  PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(latestSnapshots.toList, deploymentConfigs.toList, date)
          _                 <- executePlanOfWork(planOfWork, environment)
        yield ()

  private[persistence] def executePlanOfWork(planOfWork: PlanOfWork, environment: Environment): Future[Unit] =
    logger.debug(s"Processing `ResourceUsage`s for ${environment.asString}")

    def insertSnapshot(snapshot: ResourceUsage) =
      withSessionAndTransaction: session =>
        for
          res <- collection.updateMany(
                  session,
                  filter = and(
                            equal("serviceName", snapshot.serviceName),
                            equal("environment", snapshot.environment),
                            equal("latest"     , true)
                          ),
                  update = set("latest", false)
                )
                .toFuture()
          _ <- collection.insertOne(session, snapshot).toFuture()
        yield ()

    def reintroduceServiceSnapshot(snapshot: ResourceUsage) =
      logger.debug(s"Creating a snapshot for reintroduced service: $snapshot")
      withSessionAndTransaction: session =>
        for
          _ <- removeLatestFlagForServiceInEnvironment(
                 snapshot.serviceName,
                 snapshot.environment,
                 session
               )
          _ <- collection.insertOne(session, snapshot).toFuture()
        yield ()

    for
      _ <- planOfWork.snapshots.foldLeftM(()) { case (_, snapshot) => insertSnapshot(snapshot) }
           // reintroductions are treated separately to ensure latest flag is removed from previously deleted entries -
           // not covered by above bulk flag removal
      _ <- planOfWork.snapshotServiceReintroductions.foldLeftM(())((_, ssr) => reintroduceServiceSnapshot(ssr))
    yield ()

object ResourceUsageRepository:
  final case class PlanOfWork(
    snapshots                     : List[ResourceUsage],
    snapshotServiceReintroductions: List[ResourceUsage]
  )

  object PlanOfWork:
    def fromLatestSnapshotsAndCurrentDeploymentConfigs(
      latestSnapshots         : List[ResourceUsage],
      currentDeploymentConfigs: List[DeploymentConfig],
      date                    : Instant
    ): PlanOfWork =
      val latestSnapshotsByNameAndEnv =
        latestSnapshots
          .map(s => (s.serviceName, s.environment) -> s)
          .toMap

      val planOfWork =
        currentDeploymentConfigs
          .foldLeft(PlanOfWork(List.empty[ResourceUsage], List.empty[ResourceUsage])):
            case (acc, deploymentConfig) =>
              val deploymentConfigSnapshot =
                ResourceUsage(
                  date        = date,
                  serviceName = deploymentConfig.serviceName,
                  environment = deploymentConfig.environment,
                  slots       = deploymentConfig.slots,
                  instances   = deploymentConfig.instances,
                  latest      = true,
                  deleted     = false
                )
              latestSnapshotsByNameAndEnv
                .get((deploymentConfig.serviceName, deploymentConfig.environment)) match
                  case Some(currentLatest) if currentLatest.deleted =>
                    acc.copy(snapshotServiceReintroductions = deploymentConfigSnapshot +: acc.snapshotServiceReintroductions)
                  case Some(currentLatest) if currentLatest.slots     == deploymentConfig.slots &&
                                              currentLatest.instances == deploymentConfig.instances =>
                    acc // no change, ignore
                  case _ =>
                    acc.copy(snapshots = deploymentConfigSnapshot +: acc.snapshots)

      val snapshotSynthesisedDeletions =
        val nameAndEnvForCurrentDeploymentConfigs =
          currentDeploymentConfigs.map(c => (c.serviceName, c.environment))

        (latestSnapshotsByNameAndEnv -- nameAndEnvForCurrentDeploymentConfigs)
          .values
          .filterNot(_.deleted)
          .map(synthesiseDeletedDeploymentConfigSnapshot(_, date))

      planOfWork.copy(snapshots = planOfWork.snapshots ++ snapshotSynthesisedDeletions)

    private def synthesiseDeletedDeploymentConfigSnapshot(
      snapshot: ResourceUsage,
      date    : Instant
    ): ResourceUsage =
      snapshot.copy(
        date      = date,
        slots     = 0,
        instances = 0,
        latest    = true,
        deleted   = true
      )
