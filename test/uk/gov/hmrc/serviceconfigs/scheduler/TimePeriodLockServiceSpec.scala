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

import org.mongodb.scala.model.Filters
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.mongo.CurrentTimestampSupport
import uk.gov.hmrc.mongo.lock.Lock
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, DurationInt}

class TimePeriodLockServiceSpec
  extends AnyWordSpecLike
     with Matchers
     with DefaultPlayMongoRepositorySupport[Lock]
     with OptionValues {

  "withRenewedLock" should {
    "execute the body if no previous lock is set" in {
      var counter = 0
      lockService.withRenewedLock {
        Future.successful(counter += 1)
      }.futureValue
      counter shouldBe 1
    }

    "leave the lock to expire" in {
      lockService.withRenewedLock {
        Future.unit
      }.futureValue
      val lock = repository.collection.find[Lock](Filters.equal(Lock.id, lockId)).headOption().futureValue
      lock shouldBe defined
    }

    "not execute the body if the lock for same serverId exists" in {
      // TODO but confirm it has refreshed
      val counter = new AtomicInteger(0)
      lockService.withRenewedLock {
        Future.successful(counter.incrementAndGet())
      }.futureValue

      val lock = repository.collection.find[Lock](Filters.equal(Lock.id, lockId)).headOption().futureValue

      lockService.withRenewedLock {
        Future.successful(counter.incrementAndGet())
      }.futureValue

      counter.get() shouldBe 1

      // expiryTime should have been increased
      val lock2 = repository.collection.find[Lock](Filters.equal(Lock.id, lockId)).headOption().futureValue
      lock2.value.expiryTime should not be lock.value.expiryTime
    }

    "not execute the body and exit if the lock for another serverId exists" in {
      val counter = new AtomicInteger(0)
      TimePeriodLockService(repository, lockId, timestampSupport, ttl)
        .withRenewedLock {
          Future.successful(counter.incrementAndGet())
        }
        .futureValue

      lockService.withRenewedLock {
        Future.successful(counter.incrementAndGet())
      }.futureValue

      counter.get() shouldBe 1
    }

    "execute the body if run after the ttl time has expired" in {
      val counter = new AtomicInteger(0)
      lockService.withRenewedLock {
        Future.successful(counter.incrementAndGet())
      }.futureValue

      Thread.sleep(1000 + 1)

      lockService.withRenewedLock {
        Future.successful(counter.incrementAndGet())
      }.futureValue

      counter.get() shouldBe 2
    }

    "release the lock if body fails" in {
      val e = new RuntimeException("boom")
      lockService.withRenewedLock {
        Future.failed(e)
      }.failed.futureValue shouldBe e
      val lock = repository.collection.find[Lock](Filters.equal(Lock.id, lockId)).headOption().futureValue
      lock should not be defined
    }

    "release the lock if body takes longer than expected to run" in {
      lockService.withRenewedLock {
        Thread.sleep(1000 + 1)
        Future.unit
      }.futureValue
      val lock = repository.collection.find[Lock](Filters.equal(Lock.id, lockId)).headOption().futureValue
      lock should not be defined
    }
  }

  private val lockId        = "lockId"
  private val ttl: Duration = 1000.millis
  private val timestampSupport = new CurrentTimestampSupport

  override protected val repository = new MongoLockRepository(mongoComponent, timestampSupport)
  private val lockService           = TimePeriodLockService(repository, lockId, timestampSupport, ttl)
}
