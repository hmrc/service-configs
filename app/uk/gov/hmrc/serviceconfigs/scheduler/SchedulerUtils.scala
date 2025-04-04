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
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.ScheduledLockService
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfig

import scala.concurrent.{ExecutionContext, Future}

trait SchedulerUtils extends Logging:
  private def schedule(
    label          : String
  , schedulerConfig: SchedulerConfig
  )(f: => Future[Unit]
  )(using
    actorSystem         : ActorSystem,
    applicationLifecycle: ApplicationLifecycle,
    ec                  : ExecutionContext
  ): Unit =
    if schedulerConfig.enabled then
      val initialDelay = schedulerConfig.initialDelay
      val interval     = schedulerConfig.interval
      logger.info(s"Scheduler $label enabled, running every $interval (after initial delay $initialDelay)")
      val cancellable =
        actorSystem.scheduler.scheduleWithFixedDelay(initialDelay, interval): () =>
          val start = System.currentTimeMillis
          logger.info(s"Scheduler $label started")
          f.map: res =>
            logger.info(s"Scheduler $label finished - took ${System.currentTimeMillis - start} millis")
            res
          .recover:
            case e => logger.error(s"Scheduler $label interrupted after ${System.currentTimeMillis - start} millis because: ${e.getMessage}", e)

      applicationLifecycle.addStopHook(() => Future.successful(cancellable.cancel()))
    else
      logger.info(s"Scheduler $label is DISABLED. to enable, configure configure ${schedulerConfig.enabledKey}=true in config.")

  def scheduleWithTimePeriodLock(
    label          : String
  , schedulerConfig: SchedulerConfig
  , lock           : ScheduledLockService
  )(f: => Future[Unit]
  )(using
     ActorSystem,
     ApplicationLifecycle,
     ExecutionContext
  ): Unit =
    schedule(label, schedulerConfig):
      lock
        .withLock(f)
        .map:
          case Some(_) => logger.debug(s"Scheduler $label finished - releasing lock")
          case None    => logger.debug(s"Scheduler $label cannot run - lock ${lock.lockId} is taken... skipping update")

  import cats.data._
  import cats.implicits._

  type WriterT2 [A] = WriterT[Future, List[Throwable], A]
  type ScheduledItem[A] = ReaderT[WriterT2, Option[Throwable], A]

  def runAllAndFailWithFirstError(k: ScheduledItem[Unit])(using ec: ExecutionContext) =
    k.run(None)
     .run
     .flatMap(_._1.headOption.fold(Future.unit)(Future.failed))

  def accumulateErrors(name: String, f: Future[Unit])(using ec: ExecutionContext): ScheduledItem[Unit] =
    for
      _  <- ReaderT.pure(logger.info(s"Starting scheduled task: $name"))
      op <- ReaderT.liftF(WriterT.liftF(
             f.map: _ =>
                logger.info(s"Successfully run scheduled task: $name")
                None
              .recover:
                case e => logger.error(s"Error running scheduled task $name", e)
                          Some(e)
            )): ScheduledItem[Option[Throwable]]
      re <- op.fold(ReaderT.pure(()): ScheduledItem[Unit])(ex => ReaderT.liftF(WriterT.tell(List(ex)))): ScheduledItem[Unit]
    yield re

object SchedulerUtils extends SchedulerUtils
