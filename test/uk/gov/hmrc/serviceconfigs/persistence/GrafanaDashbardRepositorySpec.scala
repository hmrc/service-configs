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
import uk.gov.hmrc.serviceconfigs.model.{Dashboard, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class GrafanaDashboardRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[Dashboard]:

  override val repository: GrafanaDashboardRepository =
    GrafanaDashboardRepository(mongoComponent)

  "GrafanaDashboardRepository" should:
    "put and retrieve" in:
      val dashboard1 = Dashboard(serviceName = ServiceName("testName1"), location = "1")
      val dashboard2 = Dashboard(serviceName = ServiceName("testName2"), location = "2")
      repository.putAll(Seq(dashboard1, dashboard2)).futureValue

      repository.findByService(dashboard1.serviceName).futureValue shouldBe Some(dashboard1)
      repository.findByService(dashboard2.serviceName).futureValue shouldBe Some(dashboard2)

      repository.putAll(Seq(dashboard1.copy(location = "2"))).futureValue
      repository.findByService(dashboard1.serviceName).futureValue shouldBe Some(dashboard1.copy(location = "2"))
      repository.findByService(dashboard2.serviceName).futureValue shouldBe None
