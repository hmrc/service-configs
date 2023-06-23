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

  "AppliedConfigRepositoryNoIndexCheckSpec with no index check" should {
    "search config value equal to" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> ("v1", "some-source"), "k2" -> ("v2", "some-source"))).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> ("v1", "some-source"), "k2" -> ("", "some-source"))).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v2")
      , valueFilterType = FilterType.EqualTo
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k2", Map(Environment.Development ->  EnvironmentData("v2", "some-source"), Environment.QA ->  EnvironmentData("" , "some-source")), false)
      )
    }

    "search config value not equal to" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> ("v1", "some-source"), "k2" -> ("v2", "some-source"))).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> ("v1", "some-source"), "k2" -> ("v2", "some-source"))).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v2")
      , valueFilterType = FilterType.NotEqualTo
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development ->  EnvironmentData("v1", "some-source"), Environment.QA ->  EnvironmentData("v1", "some-source")), false)
      )
    }

    "search config value contains" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> ("v1", "some-source"), "k2" -> ("v2", "some-source"))).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> ("v1", "some-source"), "k2" -> ("", "some-source"))).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v")
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development ->  EnvironmentData("v1", "some-source"), Environment.QA ->  EnvironmentData("v1", "some-source")), false)
      , AppliedConfig(serviceName1, "k2", Map(Environment.Development ->  EnvironmentData("v2", "some-source"), Environment.QA ->  EnvironmentData(""  , "some-source")), false)
      )
    }

    "search config value does not contain" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> ("v1", "some-source"), "k2" -> ("v2", "some-source"))).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> ("v1", "some-source"), "k2" -> ("v2", "some-source"))).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("v")
      , valueFilterType = FilterType.DoesNotContain
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should be (Nil)
    }

    "search config value is empty" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> ("v1", "some-source"), "k2" -> ("v2", "some-source"))).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> ("v1", "some-source"), "k2" -> ("", "some-source"))).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.IsEmpty
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k2", Map(Environment.Development ->  EnvironmentData("v2", "some-source"), Environment.QA ->  EnvironmentData(""  , "some-source")), false)
      )

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = Some("blah blah blah") // should be ignored
      , valueFilterType = FilterType.IsEmpty
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k2", Map(Environment.Development ->  EnvironmentData("v2", "some-source"), Environment.QA ->  EnvironmentData(""  , "some-source")), false)
      )
    }

    "search limited" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, (1 to configSearchLimit + 2).map(i => s"k$i" -> (s"v$i", "some-source")).toMap).futureValue
      repository.put(serviceName1, Environment.QA, (1 to configSearchLimit + 2).map(i => s"k$i" -> (s"v$i", "some-source")).toMap).futureValue

      repository.search(
        key             = None
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments     = Seq.empty
      , serviceNames    = None
      ).futureValue.size should be (configSearchLimit + 1)
    }
  }
}
