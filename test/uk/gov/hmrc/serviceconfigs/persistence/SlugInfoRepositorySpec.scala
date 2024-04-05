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

import java.time.Instant

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{ServiceName, SlugInfo, SlugInfoFlag, Version}

import scala.concurrent.ExecutionContext

class SlugInfoRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[SlugInfo] {

  import ExecutionContext.Implicits.global

  override lazy val repository = new SlugInfoRepository(mongoComponent)

  "SlugInfoRepository" should {
    "manage by version" in {
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 1, 0))).futureValue
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 0, 0))).futureValue
      repository.add(sampleSlugInfo(ServiceName("other-slug"), Version(1, 0, 0))).futureValue

      repository.getSlugInfos(ServiceName("my-slug"), version = None).futureValue shouldBe Seq(
        sampleSlugInfo(ServiceName("my-slug"), Version(1, 1, 0)),
        sampleSlugInfo(ServiceName("my-slug"), Version(1, 0, 0))
      )

      repository.getSlugInfos(ServiceName("my-slug"), Some(Version(1, 1, 0))).futureValue shouldBe Seq(
        sampleSlugInfo(ServiceName("my-slug"), Version(1, 1, 0))
      )
    }

    "manage by flag" in {
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 1, 0))).futureValue
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 0, 0))).futureValue
      repository.add(sampleSlugInfo(ServiceName("other-slug"), Version(1, 0, 0))).futureValue
      repository.setFlag(SlugInfoFlag.Latest, ServiceName("my-slug"), Version(1, 1, 0)).futureValue
      repository.setFlag(SlugInfoFlag.Latest, ServiceName("other-slug"), Version(1, 0, 0)).futureValue

      repository.getSlugInfo(ServiceName("my-slug"), SlugInfoFlag.Latest).futureValue shouldBe Some(
        sampleSlugInfo(ServiceName("my-slug"), Version(1, 1, 0))
      )

      repository.getAllLatestSlugInfos().futureValue shouldBe Seq(
        sampleSlugInfo(ServiceName("my-slug"), Version(1, 1, 0)),
        sampleSlugInfo(ServiceName("other-slug"), Version(1, 0, 0))
      )

      repository.clearFlags(SlugInfoFlag.Latest, Seq(ServiceName("my-slug"), ServiceName("other-slug"))).futureValue
      repository.getAllLatestSlugInfos().futureValue shouldBe Seq.empty
    }

    "return the max version" in {
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 1, 0))).futureValue
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 0, 0))).futureValue
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 4, 1))).futureValue
      repository.add(sampleSlugInfo(ServiceName("my-slug"), Version(1, 4, 0))).futureValue

      repository.getMaxVersion(ServiceName("my-slug")).futureValue shouldBe Some(Version(1, 4, 1))
    }

    "return no max version when no previous slugs exist" in {
      repository.getMaxVersion(ServiceName("non-existing-slug")).futureValue shouldBe None
    }
  }

  def sampleSlugInfo(name: ServiceName, version: Version): SlugInfo =
    SlugInfo(
      created           = Instant.parse("2019-06-28T11:51:23.000Z"),
      uri               = s"/${name.asString}/${version.original}",
      name              = name,
      version           = version,
      classpath         = "",
      dependencies      = List.empty,
      applicationConfig = "",
      includedAppConfig = Map.empty,
      loggerConfig      = "",
      slugConfig        = ""
    )
}
