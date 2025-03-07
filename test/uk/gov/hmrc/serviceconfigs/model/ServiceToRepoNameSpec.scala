/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.model

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{JsResult, JsSuccess, JsValue, Json, Reads}

class ServiceToRepoNameSpec extends AnyWordSpec with Matchers:

  "service-to-repo-name.json" should:
    "be valid and correctly parsed" in:
      val json  : JsValue                           = Json.parse(getClass.getResourceAsStream("/resources/service-to-repo-names.json"))
      val result: JsResult[List[ServiceToRepoName]] = json.validate[List[ServiceToRepoName]](Reads.list(ServiceToRepoName.reads))
      result shouldBe a[JsSuccess[_]]
