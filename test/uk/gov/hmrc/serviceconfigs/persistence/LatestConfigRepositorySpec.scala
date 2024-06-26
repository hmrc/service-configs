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
import uk.gov.hmrc.serviceconfigs.model.{FileName, Content}

import scala.concurrent.ExecutionContext.Implicits.global

class LatestConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[LatestConfigRepository.LatestConfig]:

  override protected val repository: LatestConfigRepository =
    LatestConfigRepository(mongoComponent)

  "LatestConfigRepository.put" should:
    "put correctly" in:
      val repoName1 = "app-config-base"
      repository.put(repoName1)(Map(FileName("file1") -> Content("content1"), FileName("file2") -> Content("content2"))).futureValue
      val repoName2 = "app-config-production"
      repository.put(repoName2)(Map(FileName("file3") -> Content("content3"), FileName("file4") -> Content("content4"))).futureValue

      repository.find(repoName1, "file1").futureValue shouldBe Some("content1")
      repository.find(repoName1, "file2").futureValue shouldBe Some("content2")
      repository.find(repoName2, "file3").futureValue shouldBe Some("content3")
      repository.find(repoName2, "file4").futureValue shouldBe Some("content4")

      repository.put(repoName1)(Map(FileName("file1") -> Content("content3"))).futureValue
      //put will replace all content for that repoName, but not affect other repoNames
      repository.find(repoName1, "file1").futureValue shouldBe Some("content3")
      repository.find(repoName1, "file2").futureValue shouldBe None
      repository.find(repoName2, "file3").futureValue shouldBe Some("content3")
      repository.find(repoName2, "file4").futureValue shouldBe Some("content4")

  "LatestConfigRepository.findall" should:
    "find correctly" in:
      val repoName1 = "app-config-base"
      repository.put(repoName1)(Map(FileName("file1") -> Content("content1"), FileName("file2") -> Content("content2"))).futureValue
      val repoName2 = "app-config-production"
      repository.put(repoName2)(Map(FileName("file3") -> Content("content3"), FileName("file4") -> Content("content4"))).futureValue

      repository.findAll(repoName2).futureValue shouldBe
        Seq(
          LatestConfigRepository.LatestConfig(repoName = repoName2, FileName("file3"), Content("content3")),
          LatestConfigRepository.LatestConfig(repoName = repoName2, FileName("file4"), Content("content4"))
        )
