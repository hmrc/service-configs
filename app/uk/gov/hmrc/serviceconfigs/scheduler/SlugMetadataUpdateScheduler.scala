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

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.serviceconfigs.service.SlugInfoService

import scala.concurrent.ExecutionContext

@Singleton
class SlugMetadataUpdateScheduler @Inject()(
  configuration       : Configuration,
  slugInfoService     : SlugInfoService,
  mongoLockRepository : MongoLockRepository,
  timestampSupport    : TimestampSupport
)(using
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils:

  private given HeaderCarrier = HeaderCarrier()

  private val schedulerConfig = SchedulerConfig(configuration, "slug-metadata-scheduler")

  scheduleWithTimePeriodLock(
    label           = "SlugMetadataUpdateScheduler",
    schedulerConfig = schedulerConfig,
    lock            = ScheduledLockService(mongoLockRepository, "slug-metadata-scheduler", timestampSupport, schedulerConfig.interval)
  ):
    logger.info("Updating slug metadata")
    for
      _ <- slugInfoService.updateMetadata()
    yield logger.info("Finished updating slug metadata")
