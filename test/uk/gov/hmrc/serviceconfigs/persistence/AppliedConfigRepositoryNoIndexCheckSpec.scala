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
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport

import scala.concurrent.ExecutionContext.Implicits.global
import uk.gov.hmrc.serviceconfigs.model.{Environment, FilterType, ServiceName}


class AppliedConfigRepositoryNoIndexCheckSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[AppliedConfigRepository.AppliedConfig] {
  import AppliedConfigRepository._

  val configSearchLimit = 5
  private val config = play.api.Configuration("config-search.max-limit" -> configSearchLimit)
  override protected val repository = new AppliedConfigRepository(config, mongoComponent)

  // Disble index check for value search since it requires a group by
  override protected def checkIndexedQueries = false

  "AppliedConfigRepository with no index check" should {
    "search config value equal to" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "v2")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k2" -> "")).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v2")
      , valueFilterType = FilterType.EqualTo
      , environment     = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k2", "v2"),
        AppliedConfig(Environment.QA         , serviceName1, "k2", ""),
      )
    }

    "search config value not equal to" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "v2")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k2" -> "")).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v2")
      , valueFilterType = FilterType.NotEqualTo
      , environment     = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
      )
    }

    "search config value contains" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "v2")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k2" -> "")).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v")
      , valueFilterType = FilterType.Contains
      , environment     = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k1", "v1"),
        AppliedConfig(Environment.Development, serviceName1, "k2", "v2"),
        AppliedConfig(Environment.QA         , serviceName1, "k1", "v1"),
        AppliedConfig(Environment.QA         , serviceName1, "k2", ""),
      )
    }

    "search config value does not contain" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "v2")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k2" -> "")).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v")
      , valueFilterType = FilterType.DoesNotContain
      , environment     = Seq.empty
      , serviceNames    = None
      ).futureValue should be (Nil)
    }

    "search config value is empty" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(Environment.Development, serviceName1, Map("k1" -> "v1", "k2" -> "v2")).futureValue
      repository.put(Environment.QA         , serviceName1, Map("k1" -> "v1", "k2" -> "")).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.IsEmpty
      , environment     = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k2", "v2"),
        AppliedConfig(Environment.QA         , serviceName1, "k2", ""),
      )

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("blah blah blah") // should be ignored
      , valueFilterType = FilterType.IsEmpty
      , environment     = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(Environment.Development, serviceName1, "k2", "v2"),
        AppliedConfig(Environment.QA         , serviceName1, "k2", ""),
      )
    }

    "search limited" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(Environment.Development, serviceName1, (1 to configSearchLimit + 2).map(i => s"k$i" -> s"v$i").toMap).futureValue
      repository.put(Environment.QA, serviceName1, (1 to configSearchLimit + 2).map(i => s"k$i" -> s"v$i").toMap).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environment     = Seq.empty
      , serviceNames    = None
      ).futureValue.size should be (configSearchLimit + 1)
    }
  }
}
