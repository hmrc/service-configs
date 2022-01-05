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
import javax.inject.Inject
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, LockService}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.service.SlugInfoService

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt


class SlugMetadataUpdateScheduler @Inject()(
    schedulerConfigs    : SchedulerConfigs,
    slugInfoService     : SlugInfoService,
    mongoLockRepository : MongoLockRepository
  )(implicit
    actorSystem         : ActorSystem,
    applicationLifecycle: ApplicationLifecycle,
    ec                  : ExecutionContext
  ) extends SchedulerUtils
    with Logging {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  scheduleWithLock(
    "Slug Metadata Updater",
    schedulerConfigs.slugMetadataUpdate,
    LockService(mongoLockRepository, "slug-metadata-scheduler", 1.hour)
  ) {
    logger.info("Updating slug metadata")
    for {
      _ <- slugInfoService.updateMetadata()
      _ = logger.info("Finished updating slug metadata")
    } yield ()
  }
}
