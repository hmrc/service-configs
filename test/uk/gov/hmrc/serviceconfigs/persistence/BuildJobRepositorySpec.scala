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
import uk.gov.hmrc.serviceconfigs.model.{BuildJob, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class BuildJobDashboardRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[BuildJob] {

  override lazy val repository = new BuildJobRepository(mongoComponent)

  "BuildJobRepository" should {
    "put and retrieve" in {
      val buildJob1 = BuildJob(service = ServiceName("testName1"), location = "1")
      val buildJob2 = BuildJob(service = ServiceName("testName2"), location = "2")
      repository.putAll(Seq(buildJob1, buildJob2)).futureValue

      repository.findByService(buildJob1.service).futureValue shouldBe Some(buildJob1)
      repository.findByService(buildJob2.service).futureValue shouldBe Some(buildJob2)

      repository.putAll(Seq(buildJob1.copy(location = "2"))).futureValue
      repository.findByService(buildJob1.service).futureValue shouldBe Some(buildJob1.copy(location = "2"))
      repository.findByService(buildJob2.service).futureValue shouldBe None
    }
  }
}
