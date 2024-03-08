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
import uk.gov.hmrc.serviceconfigs.model.{ArtefactName, DeploymentConfig, Environment, ServiceName}

class DeploymentConfigServiceSpec
  extends AnyWordSpec
     with Matchers
     with OptionValues {
  import DeploymentConfigService._

  "toDeploymentConfig" should {
    "discard the 0.0.0 root element, hmrc_config and add name and environment" in {
      toDeploymentConfig(
        serviceName = ServiceName("service-name")
      , environment = Environment.Production
      , applied     = true
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

      ) shouldBe Some(DeploymentConfig(
        serviceName    = ServiceName("service-name")
      , artefactName   = Some(ArtefactName("some-alternative-service-name"))
      , environment    = Environment.Production
      , zone           = "public"
      , deploymentType = "frontend"
      , slots          = 3
      , instances      = 1
      , envVars        = Map.empty
      , jvm            = Map.empty
      , applied        = true
      ))
    }

    "return None when there is no 0.0.0 root element" in {
      toDeploymentConfig(
        serviceName = ServiceName("service-name")
      , environment = Environment.Production
      , applied     = true
      , fileContent = s"""
                      |any-other-key: {}
                      |""".stripMargin
      ) shouldBe None
    }

    "returns None when the config is missing required keys" in {
      toDeploymentConfig(
        serviceName = ServiceName("service-name")
      , environment = Environment.Production
      , applied     = true
      , fileContent = s"""
                      |0.0.0:
                      |  type: frontend
                      |  slots: 1
                      |  instances: 1
                      |""".stripMargin
      ) shouldBe None
    }

    "keep env vars and jvm config when available" in {
      toDeploymentConfig(
        serviceName = ServiceName("service-name")
      , environment = Environment.Production
      , applied     = true
      , fileContent = s"""
                      |0.0.0:
                      |  type: frontend
                      |  slots: 3
                      |  instances: 1
                      |  environment:
                      |    REAUTH_KS: ENC[GPG,hQIMA3xHC82vb7loAQ/6A4Aa+iszRkQ]
                      |  jvm:
                      |    mx: 512m
                      |    ms: 512m
                      |    "X:-OmitStackTrace": InFastThrow
                      |    "X:MaxJavaStackTraceDepth=128": ''
                      |  hmrc_config:
                      |    foo: 'true'
                      |    bar: 'false'
                      |  zone: public
                      |""".stripMargin

      ) shouldBe Some(DeploymentConfig(
        serviceName    = ServiceName("service-name")
      , artefactName   = None
      , environment    = Environment.Production
      , zone           = "public"
      , deploymentType = "frontend"
      , slots          = 3
      , instances      = 1
      , envVars        = Map("REAUTH_KS" -> "ENC[...]")
      , jvm            = Map(
                           "mx"                           -> "512m",
                           "ms"                           -> "512m",
                           "X:-OmitStackTrace"            -> "InFastThrow",
                           "X:MaxJavaStackTraceDepth=128" -> ""
                         )
      , applied        = true
      ))
    }
  }
}
