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

class AppConfigCommonRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[AppConfigCommonRepository.AppConfigCommon] {

  override protected val repository = new AppConfigCommonRepository(mongoComponent)

  "AppConfigCommonRepository" should {
    "putAllHEAD correctly" in {
      repository.putAllHEAD(Map("file1" -> "content1", "file2" -> "content2")).futureValue
      repository.put(serviceName = "service1", fileName = "file3", commitId = "1234", content = "content3")

      repository.findHEAD("file1").futureValue shouldBe Some("content1")
      repository.findHEAD("file2").futureValue shouldBe Some("content2")
      repository.find("service1", "file3").futureValue shouldBe Some("content3")

      repository.putAllHEAD(Map("file1" -> "content3")).futureValue
      //putAllHEAD will replace all content without a serviceName, but not affect those associated with a serviceName
      repository.findHEAD("file1").futureValue shouldBe Some("content3")
      repository.findHEAD("file2").futureValue shouldBe None
      // it will also not affect those associated with a service-name
      repository.find("service1", "file3").futureValue shouldBe Some("content3")
    }

    "put correctly" in {
      repository.put(serviceName = "service1", fileName = "file1", commitId = "1234", content = "content1")
      repository.put(serviceName = "service2", fileName = "file2", commitId = "5678", content = "content2")

      repository.find(serviceName = "service1", fileName= "file1").futureValue shouldBe Some("content1")
      repository.find(serviceName = "service2", fileName= "file2").futureValue shouldBe Some("content2")
      repository.find(serviceName = "service1", fileName= "file2").futureValue shouldBe None
    }
  }
}
