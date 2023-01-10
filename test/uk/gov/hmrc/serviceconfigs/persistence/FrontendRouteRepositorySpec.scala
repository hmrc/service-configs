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

class FrontendRouteRepositorySpec extends AnyWordSpec with Matchers {

  "FrontendRouteRepository.pathsToRegex" should {
    "create regex from paths" in {
      FrontendRouteRepository.pathsToRegex(Seq(""))                   shouldBe "^(\\^)?(\\/)?(\\/|$)"
      FrontendRouteRepository.pathsToRegex(Seq("account"))            shouldBe "^(\\^)?(\\/)?account(\\/|$)"
      FrontendRouteRepository.pathsToRegex(Seq("account", "welcome")) shouldBe "^(\\^)?(\\/)?account\\/welcome(\\/|$)"
    }

    "escape '-'" in {
      FrontendRouteRepository.pathsToRegex(Seq("account-a", "welcome-b")) shouldBe "^(\\^)?(\\/)?account(-|\\\\-)a\\/welcome(-|\\\\-)b(\\/|$)"
    }
  }

  "FrontendRouteRepository.queries" should {
    "create queries from path" in {
      FrontendRouteRepository.queries("a/b/c").toList shouldBe Seq(
        FrontendRouteRepository.toQuery(Seq("a", "b", "c")),
        FrontendRouteRepository.toQuery(Seq("a", "b")),
        FrontendRouteRepository.toQuery(Seq("a")))
    }
  }
}
