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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{Environment, FilterType, ServiceName}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.RenderedConfigSourceValue

import scala.concurrent.ExecutionContext.Implicits.global

class AppliedConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[AppliedConfigRepository.AppliedConfig] {
  import AppliedConfigRepository._

  val configSearchLimit = 5
  private val config = play.api.Configuration("config-search.max-limit" -> configSearchLimit)

  override protected val repository: AppliedConfigRepository = new AppliedConfigRepository(config, mongoComponent)

  "AppliedConfigRepository" should {
    "put correctly" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source", Some("some-url"), "ENC[...]"))
      ).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k4" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4"))
      ).futureValue
      val serviceName2 = ServiceName("serviceName2")
      repository.put(serviceName2, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k3" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3"))
      ).futureValue

      findAll().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName1, "k2", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "ENC[...]")), false)
      , AppliedConfig(serviceName1, "k4", Map(Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4")), false)
      , AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k3", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3")), false)
      )
    }

    "delete correctly" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2"))
      ).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k4" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4"))
      ).futureValue
      val serviceName2 = ServiceName("serviceName2")
      repository.put(serviceName2, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k3" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3"))
      ).futureValue

      findAll().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName1, "k2", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2")), false)
      , AppliedConfig(serviceName1, "k4", Map(Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4")), false)
      , AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k3", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3")), false)
      )

      repository.delete(serviceName1, Environment.Development).futureValue

      findAll().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName1, "k4", Map(Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4")), false)
      , AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k3", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3")), false)
      )

      repository.delete(serviceName1, Environment.QA).futureValue

      findAll().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k3", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3")), false)
      )
    }

    "search config key equal to" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2"))
      ).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k4" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4"))
      ).futureValue
      val serviceName2 = ServiceName("serviceName2")
      repository.put(serviceName2, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k3" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3"))
      ).futureValue

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )

      repository.search(
        key             = Some("k")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should be (Nil)
    }

    "search config key contains" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2"))
      ).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k4" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4"))
      ).futureValue
      val serviceName2 = ServiceName("serviceName2")
      repository.put(serviceName2, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k3" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3"))
      ).futureValue

      findAll().futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName1, "k2", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2")), false)
      , AppliedConfig(serviceName1, "k4", Map(Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4")), false)
      , AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k3", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3")), false)
      )

      repository.search(
        key             = Some("1")
      , keyFilterType   = FilterType.Contains
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )
    }

    "search by service names when present" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2"))
      ).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k4" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4"))
      ).futureValue
      val serviceName2 = ServiceName("serviceName2")
      repository.put(serviceName2, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k3" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3"))
      ).futureValue

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = Some(Nil)
      ).futureValue should be (Nil)

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = Some(List(serviceName1))
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = Some(List(serviceName2))
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq.empty
      , serviceNames    = Some(List(serviceName1, serviceName2))
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                          Environment.QA              -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      , AppliedConfig(serviceName2, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )
    }

    "search per environment when present" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2"))
      ).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k4" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4"))
      ).futureValue

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Nil
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq(Environment.Development)
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq(Environment.QA)
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1")), false)
      )

      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Seq(Environment.Production)
      , serviceNames    = None
      ).futureValue should be (Nil)
    }

    "return config keys" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v2"))
      ).futureValue
      repository.put(serviceName1, Environment.QA         , Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k4" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v4"))
      ).futureValue
      val serviceName2 = ServiceName("serviceName2")
      repository.put(serviceName2, Environment.Development, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"),
                                                                "k3" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v3"))
      ).futureValue

      repository.findConfigKeys(serviceNames = None).futureValue shouldBe Seq("k1", "k2", "k3", "k4")
      repository.findConfigKeys(serviceNames = Some(List(serviceName1))).futureValue shouldBe Seq("k1", "k2", "k4")
    }

    "hide reference only keys" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("referenceConf", Some("some-url"), "v1"),
                                                                "k2" -> RenderedConfigSourceValue("some-source"  , Some("some-url"), "v2"))
      ).futureValue
      repository.findConfigKeys(serviceNames = None).futureValue shouldBe (Seq("k2"))
    }

    "hide reference only searches" in {
      val serviceName1 = ServiceName("serviceName1")
      repository.put(serviceName1, Environment.Development, Map("k1" -> RenderedConfigSourceValue("referenceConf", Some("some-url"), "v1"))
      ).futureValue
      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Nil
      , serviceNames    = None
      ).futureValue shouldBe Nil

      repository.put(serviceName1, Environment.QA, Map("k1" -> RenderedConfigSourceValue("some-source", Some("some-url"), "v1"))
      ).futureValue
      repository.search(
        key             = Some("k1")
      , keyFilterType   = FilterType.EqualTo
      , value           = None
      , valueFilterType = FilterType.Contains
      , environments    = Nil
      , serviceNames    = None
      ).futureValue should contain theSameElementsAs Seq(
        AppliedConfig(serviceName1, "k1", Map(Environment.Development -> RenderedConfigSourceValue("referenceConf", Some("some-url"), "v1"),
                                              Environment.QA          -> RenderedConfigSourceValue("some-source"  , Some("some-url"), "v1")), false)
      )
    }
  }
}
