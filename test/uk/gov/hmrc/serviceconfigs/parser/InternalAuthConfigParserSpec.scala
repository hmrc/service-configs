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

package uk.gov.hmrc.serviceconfigs.parser

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.model.{InternalAuthConfig, InternalAuthEnvironment, GrantType, ServiceName}

import java.util.zip.ZipInputStream

class InternalAuthConfigParserSpec
  extends AnyWordSpec
     with Matchers:

  lazy val configZip = ZipInputStream(this.getClass.getResource("/internal-auth-config-main.zip").openStream())
  lazy val parser    = InternalAuthConfigParser()

  val grants =
    """
      |- grantees:
      |    service: [
      |      hello-world-object-store,
      |      internal-auth-perf-test,
      |    ]
      |    permissions:
      |    - resourceType: object-store
      |      resourceLocation: '{service}'
      |      actions: [ '*' ]
      |- grantees:
      |    owners-of-service: [
      |      advance-valuation-rulings-frontend,
      |      advance-valuation-rulings,
      |      child-benefit-service
      |    ]
      |    permissions:
      |    - resourceType: object-store
      |      resourceLocation: '{service}'
      |      actions: [ '*' ]
      |""".stripMargin

  val resourceTypes =
    """
      |- resourceType: internal-auth
      |  actions: [ READ, WRITE, DELETE ]
      |  granteeTypes: [ members-of-team ]
      |- resourceType: internal-auth-admin-frontend
      |  actions: [ READ ]
      |  granteeTypes: [ user ]
      |- resourceType: object-store
      |  actions: [ READ, WRITE, DELETE ]
      |  granteeTypes: [ members-of-team, owners-of-service, service ]
      |""".stripMargin

  "An InternalAuthConfigParser" should:
    "parse a zip file " in:
      val result: Set[InternalAuthConfig] = parser.parseZip(configZip)
      result.size shouldBe 218

    "parse grants as grantees" in:
      parser.parseGrants(grants, InternalAuthEnvironment.Qa) shouldBe
        Set(
          InternalAuthConfig(ServiceName("hello-world-object-store"), InternalAuthEnvironment.Qa, GrantType.Grantee),
          InternalAuthConfig(ServiceName("internal-auth-perf-test") , InternalAuthEnvironment.Qa, GrantType.Grantee)
        )

    "parse resources definitions as grantors" in:
      parser.parseGrants(resourceTypes, InternalAuthEnvironment.Prod) shouldBe
        Set(
          InternalAuthConfig(ServiceName("internal-auth")               , InternalAuthEnvironment.Prod, GrantType.Grantor),
          InternalAuthConfig(ServiceName("internal-auth-admin-frontend"), InternalAuthEnvironment.Prod, GrantType.Grantor),
          InternalAuthConfig(ServiceName("object-store")                , InternalAuthEnvironment.Prod, GrantType.Grantor)
        )
