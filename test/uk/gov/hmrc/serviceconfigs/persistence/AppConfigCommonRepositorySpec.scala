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
     with DefaultPlayMongoRepositorySupport[(String, String)] {

  override protected val repository = new AppConfigCommonRepository(mongoComponent)

  "AppConfigCommonRepository" should {
    "putAll correctly" in {
      repository.putAll(Map("file1" -> "content1", "file2" -> "content2")).futureValue
      //repository.
      repository.findByFileName("file1").futureValue shouldBe Some("content1")
      repository.findByFileName("file2").futureValue shouldBe Some("content2")

      repository.putAll(Map("file1" -> "content3")).futureValue
      repository.findByFileName("file1").futureValue shouldBe Some("content3")
      repository.findByFileName("file2").futureValue shouldBe None
    }
  }
}
