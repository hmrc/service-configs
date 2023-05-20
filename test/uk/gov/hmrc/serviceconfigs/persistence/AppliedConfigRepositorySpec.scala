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

package uk.gov.hmrc.serviceconfigs.persistence

import org.mockito.MockitoSugar
import org.mongodb.scala.bson.{BsonDocument, BsonString}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.serviceconfigs.model.Environment


class AppliedConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[AppliedConfigRepository.AppliedConfig] {
  import AppliedConfigRepository._

  override protected val repository = new AppliedConfigRepository(mongoComponent)

  "AppliedConfigRepository" should {
    "put correctly" in {
      val serviceName1 = "serviceName1"
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "ENC[1234]")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k4" -> "v4")).futureValue
      val serviceName2 = "serviceName2"
      repository.put(Environment.Development, serviceName2, Map("k1" -> "v1", "k3" -> "v3")).futureValue

      repository.collection.find().toFuture().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName1, "k2", "ENC[...]"),
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k4", "v4"),
        AppliedConfig(Environment.Development, serviceName2, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName2, "k3", "v3")
      )

      repository.put(Environment.Development, serviceName1, Map("k5" -> "v5", "k6" -> "v6")).futureValue

      repository.collection.find().toFuture().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k4", "v4"),
        AppliedConfig(Environment.Development, serviceName2, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName2, "k3", "v3"),
        AppliedConfig(Environment.Development, serviceName1, "k5", "v5"),
        AppliedConfig(Environment.Development, serviceName1, "k6", "v6")
      )
    }

    "store encrypted secrets simply" in {
      val serviceName = "serviceName"
      repository.put(Environment.Development, serviceName, Map("k" -> "ENC[1234]")).futureValue

      mongoComponent.database.getCollection[BsonDocument]("appliedConfig").find().toFuture().futureValue.map(_.get("value")) shouldBe Seq(BsonString("ENC[...]"))
    }

    "delete correctly" in {
      val serviceName1 = "serviceName1"
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "v2")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k4" -> "v4")).futureValue
      val serviceName2 = "serviceName2"
      repository.put(Environment.Development, serviceName2, Map("k1" -> "v1", "k3" -> "v3")).futureValue

      repository.collection.find().toFuture().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName1, "k2", "v2"),
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k4", "v4"),
        AppliedConfig(Environment.Development, serviceName2, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName2, "k3", "v3")
      )

      repository.delete(Environment.Development, serviceName1).futureValue

      repository.collection.find().toFuture().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k4", "v4"),
        AppliedConfig(Environment.Development, serviceName2, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName2, "k3", "v3")
      )
    }

    "find correctly" in {
      val serviceName1 = "serviceName1"
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "v2")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k4" -> "v4")).futureValue
      val serviceName2 = "serviceName2"
      repository.put(Environment.Development, serviceName2, Map("k1" -> "v1", "k3" -> "v3")).futureValue

      repository.collection.find().toFuture().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName1, "k2", "v2"),
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k4", "v4"),
        AppliedConfig(Environment.Development, serviceName2, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName2, "k3", "v3")
      )

      repository.find("k1", environment = None, serviceName = None).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName2, "k1", "v1")
      )

      repository.find("k1", environment = Some(Environment.Development), serviceName = None).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName2, "k1", "v1")
      )

      repository.find("k1", environment = None, serviceName = Some(serviceName1)).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1")
      )
    }
  }
}
