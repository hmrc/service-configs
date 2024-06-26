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

package uk.gov.hmrc.serviceconfigs.config

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration

class NginxConfigSpec
  extends AnyWordSpec
     with Matchers:

  "nginx config" should:
    "fail-fast if config is missing" in:
      val error = intercept[RuntimeException]:
        new NginxConfig(Configuration())
      error.getMessage.contains("not specified") shouldBe true

    "load the config" in:
      val nginxConfig =
        new NginxConfig(
          Configuration(
            "nginx.config-repo"                       -> "repo",
            "nginx.config-repo-branch"                -> "HEAD",
            "nginx.config-files"                      -> List("file1", "file2"),
            "nginx.shutter-killswitch-path"           -> "killswitch",
            "nginx.shutter-serviceswitch-path-prefix" -> "serviceswitch"
          ))

      nginxConfig.configRepo                                   shouldBe "repo"
      nginxConfig.frontendConfigFileNames                      shouldBe List("file1", "file2")
      nginxConfig.shutterConfig.shutterKillswitchPath          shouldBe "killswitch"
      nginxConfig.shutterConfig.shutterServiceSwitchPathPrefix shouldBe "serviceswitch"
