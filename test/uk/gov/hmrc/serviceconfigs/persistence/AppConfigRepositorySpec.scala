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
import uk.gov.hmrc.serviceconfigs.model.Environment

class AppConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[AppConfigRepository.AppConfig] {

  override protected val repository = new AppConfigRepository(mongoComponent)

  "AppConfigRepository" should {
    "putAllHEAD correctly" in {
      val repoName1 = "app-config-base"
      repository.putAllHEAD(repoName1)(Map("file1" -> "content1", "file2" -> "content2")).futureValue
      val repoName2 = "app-config-production"
      repository.putAllHEAD(repoName2)(Map("file3" -> "content3", "file4" -> "content4")).futureValue
      repository.put(repoName1, fileName = "file5", environment = Environment.Production, commitId = "1234", content = "content5")

      val noEnvironment = None // indicates we want latest/HEAD
      repository.find(repoName1, noEnvironment, "file1").futureValue shouldBe Some("content1")
      repository.find(repoName1, noEnvironment, "file2").futureValue shouldBe Some("content2")
      repository.find(repoName2, noEnvironment, "file3").futureValue shouldBe Some("content3")
      repository.find(repoName2, noEnvironment, "file4").futureValue shouldBe Some("content4")
      repository.find(repoName1, Some(Environment.Production), "file5").futureValue shouldBe Some("content5")

      repository.putAllHEAD(repoName1)(Map("file1" -> "content3")).futureValue
      //putAllHEAD will replace all content for that repoName, but not affect other repoNames
      repository.find(repoName1, noEnvironment, "file1").futureValue shouldBe Some("content3")
      repository.find(repoName1, noEnvironment, "file2").futureValue shouldBe None
      repository.find(repoName2, noEnvironment, "file3").futureValue shouldBe Some("content3")
      repository.find(repoName2, noEnvironment, "file4").futureValue shouldBe Some("content4")
      // it will also not affect non HEAD
      repository.find(repoName1, Some(Environment.Production), "file5").futureValue shouldBe Some("content5")
    }

    "put correctly" in {
      val repoName = "app-config-base"
      repository.put(repoName, fileName = "file1", environment = Environment.Production, commitId = "1234", content = "content1")
      repository.put(repoName, fileName = "file2", environment = Environment.Production, commitId = "5678", content = "content2")

      repository.find(repoName, Some(Environment.Production ), "file1").futureValue shouldBe Some("content1")
      repository.find(repoName, Some(Environment.Production ), "file2").futureValue shouldBe Some("content2")
      repository.find(repoName, Some(Environment.Development), "file1").futureValue shouldBe None
      repository.find(repoName, Some(Environment.Production ), "file3").futureValue shouldBe None
    }
  }
}
