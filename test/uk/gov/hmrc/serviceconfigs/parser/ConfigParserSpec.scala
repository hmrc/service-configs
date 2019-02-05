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

package uk.gov.hmrc.serviceconfigs.parser

import org.scalatest.{FlatSpec, Matchers}

class ConfigParserSpec extends FlatSpec with Matchers {

  "ConfigParser" should "convert config to map" in {
    val res = (new ConfigParser).loadConfResponseToMap("""
      |appName=service-configs
      |
      |# An ApplicationLoader that uses Guice to bootstrap the application.
      |play.application.loader = "uk.gov.hmrc.play.bootstrap.ApplicationLoader"
      |
      |controllers {
      |  # 300 is the default, you may need to change this according to your needs
      |  confidenceLevel = 300
      |  uk.gov.hmrc.serviceconfigs.CatalogueController = {
      |    needsAuth = false
      |    needsLogging = false
      |    needsAuditing = false
      |  }
      |}
      |""".stripMargin)
    res shouldBe Some(Map(
      "controllers.confidenceLevel" -> "300",
      "appName" -> "service-configs",
      "controllers.uk.gov.hmrc.serviceconfigs.CatalogueController.needsAuth" -> "false",
      "play.application.loader" -> "uk.gov.hmrc.play.bootstrap.ApplicationLoader",
      "controllers.uk.gov.hmrc.serviceconfigs.CatalogueController.needsAuditing" -> "false",
      "controllers.uk.gov.hmrc.serviceconfigs.CatalogueController.needsLogging" -> "false"))
  }

  it should "convert handle invalid config" in {
    (new ConfigParser).loadConfResponseToMap("") shouldBe None
    (new ConfigParser).loadConfResponseToMap("""
      |appName=
      |""".stripMargin) shouldBe None
    (new ConfigParser).loadConfResponseToMap("""
      |appName {
      |""".stripMargin) shouldBe None
  }

  it should "convert yaml to map" in {
    val res = (new ConfigParser).loadYamlResponseToMap("""
      |digital-service: Catalogue
      |
      |leakDetectionExemptions:
      | - ruleId: 'ip_addresses'
      |   filePaths:
      |      - '/test/uk/gov/hmrc/servicedependencies/model/VersionSpec.scala'
      |
      |bill-to:
      |  given  : Chris
      |  family : Dumars
      |  address:
      |    lines : 458 Walkman Dr.
      |    city  : Royal Oak
      |    state : MI
      |    postal: 48046
      |""".stripMargin)
    res shouldBe Some(Map(
      "bill-to.address.lines" -> "458 Walkman Dr.",
      "bill-to.given" -> "Chris",
      "bill-to.address.postal" -> "48046",
      "digital-service" -> "Catalogue",
      "bill-to.address.city" -> "Royal Oak",
      "bill-to.family" -> "Dumars",
      "bill-to.address.state" -> "MI",
      "leakDetectionExemptions" -> "[{ruleId=ip_addresses, filePaths=[/test/uk/gov/hmrc/servicedependencies/model/VersionSpec.scala]}]"))
  }

  it should "convert handle invalid yaml" in {
    (new ConfigParser).loadYamlResponseToMap("") shouldBe None
  }
}
