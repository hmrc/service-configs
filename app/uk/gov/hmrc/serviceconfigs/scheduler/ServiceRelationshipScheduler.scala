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

import akka.actor.ActorSystem
import play.api.inject.ApplicationLifecycle
import play.api.Logging
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.service._

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext

@Singleton
class ServiceRelationshipScheduler @Inject()(
  schedulerConfigs                  : SchedulerConfigs,
  mongoLockRepository               : MongoLockRepository,
  serviceRelationshipService        : ServiceRelationshipService,
  timestampSupport                  : TimestampSupport
)(implicit
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils with Logging {

  scheduleWithTimePeriodLock(
    label           = "ServiceRelationshipScheduler",
    schedulerConfig = schedulerConfigs.serviceRelationshipScheduler,
    lock            = TimePeriodLockService(mongoLockRepository, "service-relationship-scheduler", timestampSupport, schedulerConfigs.serviceRelationshipScheduler.interval.plus(1.minutes))
  ) {
    logger.info("Updating service relationships")
    for {
      _ <- serviceRelationshipService.updateServiceRelationships()
    } yield logger.info("Finished updating service relationships")
  }

}
