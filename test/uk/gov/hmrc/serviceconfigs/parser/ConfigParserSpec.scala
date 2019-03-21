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
import com.typesafe.config.ConfigRenderOptions

class ConfigParserSpec extends FlatSpec with Matchers {

  "ConfigParser" should "parse config as map" in {
    val res = ConfigParser.parseConfStringAsMap("""
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

  it should "handle invalid config" in {
    ConfigParser.parseConfStringAsMap("") shouldBe None
    ConfigParser.parseConfStringAsMap("""
      |appName=
      |""".stripMargin) shouldBe None
    ConfigParser.parseConfStringAsMap("""
      |appName {
      |""".stripMargin) shouldBe None
  }

  it should "parse yaml as map" in {
    val res = ConfigParser.parseYamlStringAsMap("""
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

  it should "handle invalid yaml" in {
    ConfigParser.parseYamlStringAsMap("") shouldBe None
  }

  "applyIncludes2" should "inline the include with first candidate found" in {
    val config =
      """include "included1.conf"
        |key1=val1""".stripMargin

    val includeCandidates =
      Seq(
        Config2(
            filename = "included1.conf"
          , content  = "key2=val2"
          )
      , Config2(
            filename = "included2.conf"
          , content  = "key3=val3"
          )
      , Config2(
            filename = "included1.conf"
          , content  = "key4=val4"
          )
      )
    ConfigParser.applyIncludes2(config, includeCandidates) shouldBe """key2=val2
                                                                      |key1=val1""".stripMargin
  }

  it should "inline the include recursively" in {
    val config =
      """include "included1.conf"
        |key1=val1""".stripMargin

    val includeCandidates =
      Seq(
        Config2(
            filename = "included1.conf"
          , content  = """include "included2.conf"
                         |key2=val2""".stripMargin
          )
      , Config2(
            filename = "included2.conf"
          , content  = "key3=val3"
          )
      , Config2(
            filename = "included1.conf"
          , content  = "key4=val4"
          )
      )
    ConfigParser.applyIncludes2(config, includeCandidates) shouldBe """key3=val3
                                                                      |key2=val2
                                                                      |key1=val1""".stripMargin
  }

  "combineConfigs" should "combine the configs" in {
    val configs = Seq(
      Config2(
          filename = "reference.conf"
        , content  = """include "included.conf"
                        |key1=val1""".stripMargin
        )
      , Config2(
          filename = "included.conf"
        , content  = """key2=val2""".stripMargin
        )
      , Config2(
          filename = "play/reference-overrides.conf"
        , content  = """key3=val3""".stripMargin
        )
      , Config2(
          filename = "reference.conf"
        , content  = """key3=val4""".stripMargin
        )
      , Config2(
          filename = "unreferenced.conf"
        , content  = """key4=val4""".stripMargin
        )
      )
    val combinedConfig = ConfigParser.combineConfigs(configs)
    combinedConfig.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2","key3":"val3"}"""
  }
}
