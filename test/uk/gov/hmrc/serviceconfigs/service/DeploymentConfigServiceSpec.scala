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

package uk.gov.hmrc.serviceconfigs.service

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ServiceName}

class DeploymentConfigServiceSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues {
  import DeploymentConfigService._

  "isAppConfig" should {
    "reject non yaml" in {
      isAppConfig("/test/helloworld.txt")                         shouldBe false
      isAppConfig("/app-config-production/foo/bar.xml")           shouldBe false
      isAppConfig("/app-config-production/.yaml/not-a-yaml.json") shouldBe false
    }

    "reject files in the ignore list" in {
      isAppConfig("/app-config-production/repository.yaml")    shouldBe false
      isAppConfig("/app-config-production/.github/stale.yaml") shouldBe false
    }

    "accept any other yaml file" in {
      isAppConfig("/app-config-production/test.yaml") shouldBe true
      isAppConfig("/app-config-production/auth.yaml") shouldBe true
    }
  }

  "toDeploymentConfig" should {
    "discard the 0.0.0 root element, hmrc_config and add name and environment" in {
      toDeploymentConfig(
        fileName    = "/app-config-production/test.yaml"
      , fileContent = s"""
                      |0.0.0:
                      |  type: frontend
                      |  slots: 3
                      |  instances: 1
                      |  artifact_name: some-alternative-service-name
                      |  hmrc_config:
                      |    foo: 'true'
                      |    bar: 'false'
                      |  zone: public
                      |""".stripMargin

      , environment = Environment.Production
      ) shouldBe Some(DeploymentConfig(
        serviceName    = ServiceName("test")
      , artefactName   = Some("some-alternative-service-name")
      , environment    = Environment.Production
      , zone           = "public"
      , deploymentType = "frontend"
      , slots          = 3
      , instances      = 1
      ))
    }

    "return None when there is no 0.0.0 root element" in {
      toDeploymentConfig(
        fileName    = "/app-config-production/test.yaml"
      , fileContent = s"""
                      |any-other-key: {}
                      |""".stripMargin
      , environment = Environment.Production
      ) shouldBe None
    }

    "returns None when the config is missing required keys" in {
      toDeploymentConfig(
        fileName    = "/app-config-production/test.yaml"
      , fileContent = s"""
                      |0.0.0:
                      |  type: frontend
                      |  slots: 1
                      |  instances: 1
                      |""".stripMargin
      , environment = Environment.Production
      ) shouldBe None
    }
  }
}
