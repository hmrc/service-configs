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
import uk.gov.hmrc.serviceconfigs.model.Environment

import scala.concurrent.ExecutionContext.Implicits.global

class AppConfigEnvRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[(Environment, String, String)] {

  override protected val repository = new AppConfigEnvRepository(mongoComponent)

  "AppConfigEnvRepository" should {
    "putAll correctly" in {
      repository.putAll(Environment.QA, Map("file1" -> "content1", "file2" -> "content2")).futureValue
      repository.putAll(Environment.Production, Map("file1" -> "content3", "file2" -> "content4", "file3" -> "content5")).futureValue
      //repository.
      repository.find(Environment.QA        , "file1").futureValue shouldBe Some("content1")
      repository.find(Environment.QA        , "file2").futureValue shouldBe Some("content2")
      repository.find(Environment.QA        , "file3").futureValue shouldBe None
      repository.find(Environment.Production, "file1").futureValue shouldBe Some("content3")
      repository.find(Environment.Production, "file2").futureValue shouldBe Some("content4")
      repository.find(Environment.Production, "file3").futureValue shouldBe Some("content5")

      repository.putAll(Environment.Production, Map("file1" -> "content6")).futureValue
      repository.find(Environment.QA        , "file1").futureValue shouldBe Some("content1")
      repository.find(Environment.QA        , "file2").futureValue shouldBe Some("content2")
      repository.find(Environment.QA        , "file3").futureValue shouldBe None
      repository.find(Environment.Production, "file1").futureValue shouldBe Some("content6")
      repository.find(Environment.Production, "file2").futureValue shouldBe None
      repository.find(Environment.Production, "file3").futureValue shouldBe None
    }
  }
}
