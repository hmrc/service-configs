/*
 * Copyright 2020 HM Revenue & Customs
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
import play.api.Logger
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.MongoLockService
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfig

import scala.concurrent.{ExecutionContext, Future}

trait SchedulerUtils {
  def schedule(
      label          : String
    , schedulerConfig: SchedulerConfig
    )(f: => Future[Unit]
    )(implicit actorSystem: ActorSystem, applicationLifecycle: ApplicationLifecycle, ec: ExecutionContext) =
      if (schedulerConfig.enabled) {
        val initialDelay = schedulerConfig.initialDelay
        val interval     = schedulerConfig.interval
        Logger.info(s"Enabling $label scheduler, running every $interval (after initial delay $initialDelay)")
        val cancellable =
          actorSystem.scheduler.schedule(initialDelay, interval) {
            val start = System.currentTimeMillis
            Logger.info(s"Scheduler $label started")
            f.map { res =>
              Logger.info(s"Scheduler $label finished - took ${System.currentTimeMillis - start} millis")
              res
            }
            .recover {
              case e => Logger.error(s"$label interrupted after ${System.currentTimeMillis - start} millis because: ${e.getMessage}", e)
            }
          }
        applicationLifecycle.addStopHook(() => Future(cancellable.cancel()))
      } else {
        Logger.info(s"$label scheduler is DISABLED. to enable, configure configure ${schedulerConfig.enabledKey}=true in config.")
      }

  def scheduleWithLock(
      label          : String
    , schedulerConfig: SchedulerConfig
    , lock           : MongoLockService
    )(f: => Future[Unit]
    )(implicit actorSystem: ActorSystem, applicationLifecycle: ApplicationLifecycle, ec: ExecutionContext) =
      schedule(label, schedulerConfig) {
        lock.attemptLockWithRelease(f).map {
          case Some(_) => Logger.debug(s"$label finished - releasing lock")
          case None    => Logger.debug(s"$label cannot run - lock ${lock.lockId} is taken... skipping update")
        }
  }
}

object SchedulerUtils extends SchedulerUtils