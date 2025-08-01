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
import uk.gov.hmrc.serviceconfigs.model.{AppRoutes, ServiceName, Version}

import scala.concurrent.ExecutionContext.Implicits.global

class AppRoutesRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[AppRoutes]:

  override val repository: AppRoutesRepository =
    AppRoutesRepository(mongoComponent)

  "AppRoutesRepository" should:
    "put and find" in:
      val routes =
        AppRoutes(
          service = ServiceName("test-service"),
          version = Version(1, 0, 0),
          routes  = Seq.empty
        )
      repository.put(routes).futureValue

      repository.find(serviceName = ServiceName("test-service"), version = Version(1, 0, 0)).futureValue shouldBe Some(routes)

