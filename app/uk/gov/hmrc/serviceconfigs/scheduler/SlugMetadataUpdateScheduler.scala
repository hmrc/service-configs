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
import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.scheduler.{MongoLockRepository, TimePeriodLockService}
import uk.gov.hmrc.serviceconfigs.service.SlugInfoService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

@Singleton
class SlugMetadataUpdateScheduler @Inject()(
  schedulerConfigs    : SchedulerConfigs,
  slugInfoService     : SlugInfoService,
  mongoLockRepository : MongoLockRepository,
  timestampSupport    : TimestampSupport
)(implicit
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils
  with Logging {
  private implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithTimePeriodLock(
    label           = "SlugMetadataUpdateScheduler",
    schedulerConfig = schedulerConfigs.slugMetadataScheduler,
    lock            = TimePeriodLockService(mongoLockRepository, "slug-metadata-scheduler", timestampSupport, schedulerConfigs.slugMetadataScheduler.interval.plus(1.minutes))
  ) {
    logger.info("Updating slug metadata")
    for {
      _ <- slugInfoService.updateMetadata()
    } yield logger.info("Finished updating slug metadata")
  }
}
