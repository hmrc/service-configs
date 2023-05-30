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

import uk.gov.hmrc.mongo.TimestampSupport

import java.time.{Duration => JavaDuration}
import java.util.UUID
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

/** For locking for a give time period (i.e. stop other instances executing the task until it stops renewing the lock).
  * The lock will be held on to when the task has finished, until it expires.
  *
  * This varies from the version currently available in hmrc-mongo since it will not start a new task if there is already
  * one currently running on the instance. Instead, it will refresh the lock if it is still required, and not start a new run.
  *
  * It will release the lock if there is a failure, or the scheduler finishes normally and took longer than expected to run.
  *
  * It is required to defined the expected frequency as the ttl, and run the scheduler slightly more frequent than this to
  * check if the ttl needs refreshing.
  */
trait TimePeriodLockService {

  val lockRepository: LockRepository
  val lockId: String
  val timestampSupport: TimestampSupport
  val ttl: Duration

  private val ownerId = UUID.randomUUID().toString

  /** Runs `body` if a lock can be taken or if the existing lock is owned by this service instance.
    * The lock is not released at the end of but task (unless it ends in failure), but is held onto until it expires.
    */
  def withRenewedLock[T](body: => Future[T])(implicit ec: ExecutionContext): Future[Option[T]] =
      (for {
         refreshed <- lockRepository.refreshExpiry(lockId, ownerId, ttl) // ensure our instance continues to have a valid lock in place
         acquired  <- if (!refreshed)
                        lockRepository.takeLock(lockId, ownerId, ttl)
                      else
                        Future.successful(None)
         result    <- acquired match {
                        case Some(lock) =>
                          // we only start the body if we've acquired a new lock. If we have refreshed, then we are currently running, and we don't want multiple runs in parallel
                          for {
                            res <- body
                            _   <- // if we have run longer than expected, release the lock, since another run is over-due
                                   if (timestampSupport.timestamp().toEpochMilli > lock.timeCreated.plus(JavaDuration.ofMillis(ttl.toMillis)).toEpochMilli)
                                     lockRepository.releaseLock(lockId, ownerId)
                                   else
                                     // don't release the lock, let it timeout, so nothing else starts prematurely
                                     // we remove our ownership so that we won't refresh it again, but just treat it as taken
                                     lockRepository.abandonLock(lockId)
                          } yield Some(res)
                        case None =>
                          Future.successful(None)
                      }
       } yield result
      ).recoverWith {
        case ex => // if we fail with an error, release the lock so another run can start on the earliest opportunity
                   lockRepository.releaseLock(lockId, ownerId).flatMap(_ => Future.failed(ex))
      }
}

object TimePeriodLockService {

  def apply(lockRepository: LockRepository, lockId: String, timestampSupport: TimestampSupport, ttl: Duration): TimePeriodLockService = {
    val (lockRepository1, lockId1, timestampSupport1, ttl1) = (lockRepository, lockId, timestampSupport, ttl)
    new TimePeriodLockService {
      override val lockRepository  : LockRepository   = lockRepository1
      override val lockId          : String           = lockId1
      override val timestampSupport: TimestampSupport = timestampSupport1
      override val ttl             : Duration         = ttl1
    }
  }
}
