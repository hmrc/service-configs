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

import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.model.Environment._

import org.mockito.MockitoSugar
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.concurrent.ExecutionContext.Implicits.global

class OutagePageRepositorySpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with DefaultPlayMongoRepositorySupport[OutagePage] {

  override protected val repository = new OutagePageRepository(mongoComponent)

  "OutagePageRepository" should {
    "putAll correctly" in {
      val serviceName = "service-name"
      val environments = List(Development, QA)
      repository.putAll(Seq(OutagePage(serviceName, environments))).futureValue
      
      repository.findByServiceName(serviceName).futureValue shouldBe Some(environments)
    }
  }
}
