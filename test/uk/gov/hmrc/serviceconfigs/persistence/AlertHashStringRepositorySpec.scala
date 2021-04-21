/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.persistence

import org.mockito.MockitoSugar
import org.mongodb.scala.bson.BsonDocument
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.must.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.LastHash

import scala.concurrent.ExecutionContext.Implicits.global

class AlertHashStringRepositorySpec
  extends AnyWordSpec
    with Matchers
    with MockitoSugar
    with Eventually
    with DefaultPlayMongoRepositorySupport[LastHash] {


  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(10, Seconds)), interval = scaled(Span(1000, Millis)))

  override protected val repository = new AlertHashStringRepository(mongoComponent)

  "AlertHashStringRepository" should {

    "update correctly" in {

      val lastHashString = "TestA"
      val latestHashString = "TestB"
      repository.collection.deleteMany(BsonDocument())
      Range(0, 100).foreach { _ =>
        repository.update(lastHashString).futureValue
        repository.findOne().futureValue.map(_.hash) must contain(lastHashString)
        repository.update(latestHashString).futureValue
        repository.findOne().futureValue.map(_.hash) must contain(latestHashString)
      }
    }
  }
}