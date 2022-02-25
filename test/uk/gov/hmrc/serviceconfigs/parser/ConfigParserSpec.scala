/*
 * Copyright 2022 HM Revenue & Customs
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

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import com.typesafe.config.ConfigRenderOptions
import uk.gov.hmrc.serviceconfigs.model.DependencyConfig

class ConfigParserSpec extends AnyFlatSpec with Matchers {

  "ConfigParser.flattenConfigToDotNotation" should "parse config as map" in {
    val config = ConfigParser.parseConfString(
      """
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
      |""".stripMargin
    )
    ConfigParser.flattenConfigToDotNotation(config) shouldBe Map(
      "controllers.confidenceLevel" -> "300",
      "appName" -> "service-configs",
      "controllers.uk.gov.hmrc.serviceconfigs.CatalogueController.needsAuth" -> "false",
      "play.application.loader" -> "uk.gov.hmrc.play.bootstrap.ApplicationLoader",
      "controllers.uk.gov.hmrc.serviceconfigs.CatalogueController.needsAuditing" -> "false",
      "controllers.uk.gov.hmrc.serviceconfigs.CatalogueController.needsLogging" -> "false"
    )
  }

  it should "handle substitutions" in {
    val config = ConfigParser.parseConfString(s"""
      |param1=$${akka.http.version}
      |param2=$${play.http.parser.maxMemoryBuffer}
      |""".stripMargin)
    ConfigParser.flattenConfigToDotNotation(config) shouldBe Map(
      "param1" -> s"$${akka.http.version}",
      "param2" -> s"$${play.http.parser.maxMemoryBuffer}"
    )
  }

  it should "handle overriding substitutions" in {
    val config = ConfigParser.parseConfString(s"""
    |{"cookie" {
    |   "encryption": {
    |     "key":"1",
    |     "previousKeys":["2"]
    |   }
    | }
    | "queryParameter":
    |  {
    |   "encryption":$${cookie.encryption},
    |   "encryption":{
    |     "key":"P5xsJ9Nt+quxGZzB4DeLfw==",
    |     "previousKeys":[]
    |   }
    |  }
    |}""".stripMargin)
    ConfigParser.flattenConfigToDotNotation(config) shouldBe Map(
      "cookie.encryption.key" -> "1",
      "queryParameter.encryption.key" -> "P5xsJ9Nt+quxGZzB4DeLfw==",
      "queryParameter.encryption.previousKeys" -> "[]",
      "cookie.encryption.previousKeys" -> "[\"2\"]"
    )
  }

  it should "ignore unresolvable environment substitutions" in {
    val config = ConfigParser.parseConfString(s"""
      |param1=$${?user.dir}
      |param2=$${play.http.parser.maxMemoryBuffer}
      |""".stripMargin)

    ConfigParser.flattenConfigToDotNotation(config) shouldBe Map(
      "param2" -> s"$${play.http.parser.maxMemoryBuffer}"
    )
  }

  "ConfigParser.parseYamlStringAsMap" should "parse yaml as map" in {
    val res = ConfigParser.parseYamlStringAsMap(
      """
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
      |""".stripMargin
    )
    res shouldBe Some(
      Map(
        "bill-to.address.lines" -> "458 Walkman Dr.",
        "bill-to.given" -> "Chris",
        "bill-to.address.postal" -> "48046",
        "digital-service" -> "Catalogue",
        "bill-to.address.city" -> "Royal Oak",
        "bill-to.family" -> "Dumars",
        "bill-to.address.state" -> "MI",
        "leakDetectionExemptions" -> "[{ruleId=ip_addresses, filePaths=[/test/uk/gov/hmrc/servicedependencies/model/VersionSpec.scala]}]"
      )
    )
  }

  it should "handle invalid yaml" in {
    ConfigParser.parseYamlStringAsMap("") shouldBe None
  }

  "parseConfString" should "inline the include" in {
    val config =
      """include "included1.conf"
        |key1=val1""".stripMargin

    val includeCandidates =
      Map("included1.conf" -> "key2=val2", "included2.conf" -> "key3=val3")
    val config2 = ConfigParser.parseConfString(config, includeCandidates)
    config2.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2"}"""
  }

  it should "inline the include classpath" in {
    val config =
      """include classpath("included1.conf")
        |key1=val1""".stripMargin

    val includeCandidates =
      Map("included1.conf" -> "key2=val2", "included2.conf" -> "key3=val3")
    val config2 = ConfigParser.parseConfString(config, includeCandidates)
    config2.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2"}"""
  }

  it should "inline the include recursively" in {
    val config =
      """include "included1.conf"
        |key1=val1""".stripMargin

    val includeCandidates = Map(
      "included1.conf" -> """include "included2.conf"
                              |key2=val2""".stripMargin,
      "included2.conf" -> "key3=val3"
    )
    val config2 = ConfigParser.parseConfString(config, includeCandidates)
    config2.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2","key3":"val3"}"""
  }

  it should "handle includes without extension" in {
    val config =
      """include "included1"
        |key1=val1""".stripMargin

    val includeCandidates = Map("included1.conf" -> """key2=val2""".stripMargin)
    val config2 = ConfigParser.parseConfString(config, includeCandidates)
    config2.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2"}"""
  }

  "reduceConfigs" should "combine the configs" in {
    val configs = Seq(
      dependencyConfig(
        Map(
          "reference.conf" -> """include "included.conf"
                                |key1=val1""".stripMargin,
          "included.conf" -> """key2=val2""".stripMargin
        )
      ),
      dependencyConfig(
        Map("play/reference-overrides.conf" -> """key3=val3""".stripMargin)
      ),
      dependencyConfig(Map("reference.conf" -> """key3=val4""".stripMargin)),
      dependencyConfig(Map("unreferenced.conf" -> """key4=val4""".stripMargin))
    )
    val combinedConfig = ConfigParser.reduceConfigs(configs)
    combinedConfig.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2","key3":"val3"}"""
  }

  def dependencyConfig(configs: Map[String, String]) =
    DependencyConfig(
      group = "g",
      artefact = "a",
      version = "v",
      configs = configs
    )
}
