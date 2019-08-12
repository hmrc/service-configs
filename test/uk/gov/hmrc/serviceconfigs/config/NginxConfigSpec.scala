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

package uk.gov.hmrc.serviceconfigs.config

import org.scalatest.{FunSpec, Matchers}
import play.api.Configuration

class NginxConfigSpec extends FunSpec with Matchers {

  describe("nginx config") {
    it("should fail-fast if config is missing") {
      val error = intercept[RuntimeException] {
        new NginxConfig(Configuration())
      }
      error.getMessage.contains("not specified") shouldBe true
    }
    it("should load the config") {
      val nginxConfig =
        new NginxConfig(
          Configuration(
            "nginx.config-repo" -> "repo",
            "nginx.config-files" -> List("file1", "file2"),
            "nginx.reload.enabled" -> true,
            "nginx.reload.intervalminutes" -> 25,
            "nginx.shutter-killswitch-path" -> "killswitch",
            "nginx.shutter-serviceswitch-path-prefix" -> "serviceswitch"
          ))

      nginxConfig.configRepo shouldBe "repo"
      nginxConfig.frontendConfigFileNames shouldBe List("file1", "file2")
      nginxConfig.schedulerEnabled shouldBe true
      nginxConfig.schedulerDelay shouldBe 25
      nginxConfig.shutterConfig.shutterKillswitchPath shouldBe "killswitch"
      nginxConfig.shutterConfig.shutterServiceSwitchPathPrefix shouldBe "serviceswitch"
    }
  }
}
