/*
 * Copyright 2019 HM Revenue & Customs
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

import org.scalatest.{FlatSpec, Matchers}
import uk.gov.hmrc.serviceconfigs.parser.Nginx._

class FrontendRouteRepoSpec extends FlatSpec with Matchers {

  "FrontendRouteRepo" should "create regex from paths" in {
    FrontendRouteRepo.pathsToRegex(Seq("")) shouldBe "^(\\^)?(\\/)?(\\/|$)"
    FrontendRouteRepo.pathsToRegex(Seq("account")) shouldBe "^(\\^)?(\\/)?account(\\/|$)"
    FrontendRouteRepo.pathsToRegex(Seq("account", "welcome")) shouldBe "^(\\^)?(\\/)?account\\/welcome(\\/|$)"
  }

  it should "escape '-'" in {
    FrontendRouteRepo.pathsToRegex(Seq("account-a", "welcome-b")) shouldBe "^(\\^)?(\\/)?account\\\\-a\\/welcome\\\\-b(\\/|$)"
  }
}
