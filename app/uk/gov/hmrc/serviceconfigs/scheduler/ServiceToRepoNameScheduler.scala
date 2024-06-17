/*
 * Copyright 2024 HM Revenue & Customs
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

import org.apache.pekko.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.service._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ServiceToRepoNameScheduler @Inject()(
  schedulerConfigs        : SchedulerConfigs,
  mongoLockRepository     : MongoLockRepository,
  timestampSupport        : TimestampSupport,
  serviceToRepoNameService: ServiceToRepoNameService
)(using
  actorSystem             : ActorSystem,
  applicationLifecycle    : ApplicationLifecycle,
  ec                      : ExecutionContext
) extends SchedulerUtils:

  scheduleWithTimePeriodLock(
    label           = "ServiceToRepoNameScheduler",
    schedulerConfig = schedulerConfigs.serviceToRepoNameScheduler,
    lock            = ScheduledLockService(mongoLockRepository, "service-to-repo-name-scheduler", timestampSupport, schedulerConfigs.serviceToRepoNameScheduler.interval)
  ):
    logger.info("Updating service to repo name mappings")
    for
      _ <- serviceToRepoNameService.update()
    yield logger.info("Finished updating service to repo name mappings")
