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
import play.api.inject.ApplicationLifecycle
import play.api.Configuration
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfig
import uk.gov.hmrc.serviceconfigs.persistence.SlugInfoRepository
import uk.gov.hmrc.serviceconfigs.model.SlugInfoFlag

import scala.concurrent.{Future, ExecutionContext}
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.serviceconfigs.model.ServiceName

@Singleton
class FixLatestFlagScheduler @Inject()(
  config              : Configuration,
  slugInfoRepository  : SlugInfoRepository,
  teamsAndReposConnector: TeamsAndRepositoriesConnector,
  mongoLockRepository : MongoLockRepository,
  timestampSupport    : TimestampSupport
)(using
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils:

  import cats.implicits._

  private val schedulerConfig =
    SchedulerConfig(config, "fix-latest-flag-scheduler")

  scheduleWithTimePeriodLock(
    label           = "fix-latest-flag-scheduler",
    schedulerConfig = schedulerConfig,
    lock            = ScheduledLockService(mongoLockRepository, "fix-latest-flag-scheduler", timestampSupport, schedulerConfig.interval)
  ):
    for
      decommissionedServices <- teamsAndReposConnector
                                  .getDecommissionedServices()
                                  .map(_.map(r => ServiceName(r.repoName.asString)))
      slugs                  <- slugInfoRepository.getUniqueSlugNames()
      _                      <- (slugs.toSet -- decommissionedServices.toSet)
                                  .toList
                                  .foldLeftM(()): (_, slugName) =>
                                    for
                                      oSlug <- slugInfoRepository.getSlugInfo(slugName, SlugInfoFlag.Latest)
                                      oMax  <- slugInfoRepository.getMaxVersion(slugName)
                                      _     <- (oSlug.map(_.version), oMax) match
                                                 case (Some(v1), Some(v2)) if v1 < v2 => logger.info(s"Fixing slug ${slugName.asString} latest version was ${v1.original} should be ${v2.original}")
                                                                                         slugInfoRepository.setFlag(SlugInfoFlag.Latest, slugName, v2)
                                                 case (None    , Some(v2))            => logger.info(s"Fixing slug ${slugName.asString} latest version was NOT FOUND should be ${v2.original}")
                                                                                         slugInfoRepository.setFlag(SlugInfoFlag.Latest, slugName, v2)
                                                 case _                               => Future.unit
                                    yield ()
    yield ()



