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
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.service.DeploymentConfigService

import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class DeploymentConfigScheduler @Inject()(
  schedulerConfigs       : SchedulerConfigs,
  deploymentConfigService: DeploymentConfigService,
  mongoLockRepository    : MongoLockRepository
)(implicit
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils with Logging {

  scheduleWithLock(
    "deployment config updater",
    schedulerConfigs.deploymentConfigUpdate,
    LockService(mongoLockRepository, "deployment-config-scheduler", 10.minutes)
  ){
      logger.info("starting deployment config updater")
      for {
        _ <- deploymentConfigService.updateAll()
        _ =  logger.info("finished updating deployment config")
      } yield ()
  }
}
