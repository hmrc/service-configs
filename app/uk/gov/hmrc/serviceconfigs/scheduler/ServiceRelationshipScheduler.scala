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

package uk.gov.hmrc.serviceconfigs.scheduler

import org.apache.pekko.actor.ActorSystem
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.serviceconfigs.service._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ServiceRelationshipScheduler @Inject()(
  configuration             : Configuration,
  mongoLockRepository       : MongoLockRepository,
  serviceRelationshipService: ServiceRelationshipService,
  timestampSupport          : TimestampSupport
)(using
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils:

  private val schedulerConfig = SchedulerConfig(configuration, "service-relationship-scheduler")

  scheduleWithTimePeriodLock(
    label           = "ServiceRelationshipScheduler",
    schedulerConfig = schedulerConfig,
    lock            = ScheduledLockService(mongoLockRepository, "service-relationship-scheduler", timestampSupport, schedulerConfig.interval)
  ):
    logger.info("Updating service relationships")
    for
      _ <- serviceRelationshipService.updateServiceRelationships()
    yield logger.info("Finished updating service relationships")
