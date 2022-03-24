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

package uk.gov.hmrc.serviceconfigs.scheduler

import akka.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import play.api.Logging
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

@Singleton
class DeploymentConfigSnapshotScheduler @Inject()(
  schedulerConfigs: SchedulerConfigs,
  deploymentConfigSnapshotRepository: DeploymentConfigSnapshotRepository,
  mongoLockRepository: MongoLockRepository
)(implicit
  actorSystem: ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec: ExecutionContext
) extends SchedulerUtils with Logging {

  scheduleWithLock(
    "deployment config snapshotter",
    schedulerConfigs.deploymentConfigSnapshotPopulate,
    LockService(mongoLockRepository, "deployment-config-snapshot-scheduler", 10.minutes)
  ){
    logger.info("Starting to snapshot deployment configs")
    val date = Instant.now()
    for {
      _ <- deploymentConfigSnapshotRepository.populate(date)
      _ =  logger.info("Finished snapshotting deployment configs")
    } yield ()
  }
}
