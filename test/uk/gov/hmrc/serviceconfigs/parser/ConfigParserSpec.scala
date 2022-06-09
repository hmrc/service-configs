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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import com.typesafe.config.ConfigRenderOptions
import uk.gov.hmrc.serviceconfigs.model.DependencyConfig

class ConfigParserSpec
  extends AnyWordSpec
     with Matchers {

  "ConfigParser.flattenConfigToDotNotation" should {
    "parse config as map" in {
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

    "handle unresolved substitutions" in {
      val config = ConfigParser.parseConfString(s"""
        |param1=$${akka.http.version}
        |param2=$${play.http.parser.maxMemoryBuffer}
        |""".stripMargin)

      ConfigParser.flattenConfigToDotNotation(config) shouldBe Map(
        "param1" -> s"$${akka.http.version}",
        "param2" -> s"$${play.http.parser.maxMemoryBuffer}"
      )
    }

    "handle merging of substitutions" in {
      // we can get this in practice when overriding "include"d config
      // Note, this requires substitutions to take place ir order to merge encryption
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

    "ignore unresolvable environment substitutions" in {
      val config = ConfigParser.parseConfString(s"""
        |param1=$${?user.dir}
        |param2=$${play.http.parser.maxMemoryBuffer}
        |""".stripMargin)

      ConfigParser.flattenConfigToDotNotation(config) shouldBe Map(
        "param2" -> s"$${play.http.parser.maxMemoryBuffer}"
      )
    }
  }

  "ConfigParser.parseYamlStringAsMap" should {
    "parse yaml as map" in {
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

    "handle invalid yaml" in {
      ConfigParser.parseYamlStringAsMap("") shouldBe None
    }
  }

  "ConfigParser.parseXmlLoggerConfigStringAsMap" should {
    "parse xml logger config as map" in {
      ConfigParser.parseXmlLoggerConfigStringAsMap(
        """<?xml version="1.0" encoding="UTF-8"?>
          <configuration>

            <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
              <encoder class="ch.qos.logback.classic.encoder.PatternLayoutEncoder">
                <pattern>%date [%level] [%logger] [%thread] %message%n</pattern>
              </encoder>
            </appender>

            <logger name="uk.gov" level="DEBUG"/>
            <logger name="application" level="DEBUG"/>

            <root level="INFO">
              <appender-ref ref="STDOUT"/>
            </root>
          </configuration>
        """
      ) shouldBe Some(
        Map(
          "logger.root"        -> "INFO",
          "logger.uk.gov"      -> "DEBUG",
          "logger.application" -> "DEBUG"
        )
      )
    }

    "parse xml logger config with env-var overrides as map"in {
      ConfigParser.parseXmlLoggerConfigStringAsMap(
        f"""<?xml version="1.0" encoding="UTF-8"?>
          <configuration>

            <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
                <encoder class="uk.gov.hmrc.play.logging.JsonEncoder"/>
            </appender>

            <logger name="application" level="$${logger.application:-WARN}"/>
            <logger name="uk.gov" level="$${logger.uk.gov:-WARN}"/>

            <root level="$${logger.root:-WARN}">
              <appender-ref ref="STDOUT"/>
            </root>
          </configuration>
        """
      ) shouldBe Some(
        Map(
          "logger.root"        -> f"$${logger.root:-WARN}",
          "logger.uk.gov"      -> f"$${logger.uk.gov:-WARN}",
          "logger.application" -> f"$${logger.application:-WARN}"
        )
      )
    }

    "handle invalid xml" in {
      ConfigParser.parseXmlLoggerConfigStringAsMap("") shouldBe None
    }
  }

  "ConfigParser.parseConfString" should {
    "inline the include" in {
      val config =
        """include "included1.conf"
          |key1=val1""".stripMargin

      val includeCandidates =
        Map("included1.conf" -> "key2=val2", "included2.conf" -> "key3=val3")
      val config2 = ConfigParser.parseConfString(config, includeCandidates)
      config2.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2"}"""
    }

    "inline the include classpath" in {
      val config =
        """include classpath("included1.conf")
          |key1=val1""".stripMargin

      val includeCandidates =
        Map("included1.conf" -> "key2=val2", "included2.conf" -> "key3=val3")
      val config2 = ConfigParser.parseConfString(config, includeCandidates)
      config2.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2"}"""
    }

    "inline the include recursively" in {
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

    "handle includes without extension" in {
      val config =
        """include "included1"
          |key1=val1""".stripMargin

      val includeCandidates = Map("included1.conf" -> """key2=val2""".stripMargin)
      val config2 = ConfigParser.parseConfString(config, includeCandidates)
      config2.root.render(ConfigRenderOptions.concise) shouldBe """{"key1":"val1","key2":"val2"}"""
    }
  }

  "ConfigParser.reduceConfigs" should {
    "combine the configs" in {
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
  }

  "ConfigParser.delta" should {
    "apply substitutions" in {
      val previousConf = ConfigParser.parseConfString(s"""
        |param1=asd
        |param2=$${param1}
        |""".stripMargin
      )

      val latestConf = ConfigParser.parseConfString(s"""
        |param1=yyy
        |""".stripMargin
      )

      val (conf, entries) = ConfigParser.delta(latestConf, previousConf)
      // conf should not apply substitions yet - for further updates
      conf shouldBe ConfigParser.parseConfString(s"""
        |param1=yyy
        |param2=$${param1}
        |""".stripMargin
      )
      // entries should have substitutions applied
      entries shouldBe Map(
        "param1" -> s"yyy",
        "param2" -> s"yyy"
      )
    }

    "strip untouched config" in {
      val previousConf = ConfigParser.parseConfString(s"""
        |param2=zzz
        |""".stripMargin
      )

      val latestConf = ConfigParser.parseConfString(s"""
        |param1=yyy
        |""".stripMargin
      )

      val (conf, entries) = ConfigParser.delta(latestConf, previousConf)
      // conf should contain both values - for further updates
      conf shouldBe ConfigParser.parseConfString(s"""
        |param1=yyy
        |param2=zzz
        |""".stripMargin
      )
      // entries should only include effective changes from latestConf
      entries shouldBe Map(
        "param1" -> s"yyy"
      )
    }

    "preserve explicit definitions even if the same" in {
      val previousConf = ConfigParser.parseConfString(s"""
        |param1=yyy
        |""".stripMargin
      )

      val latestConf = ConfigParser.parseConfString(s"""
        |param1=yyy
        |""".stripMargin
      )

      val (conf, entries) = ConfigParser.delta(latestConf, previousConf)
      // conf should contain the accumulated values - for further updates
      conf shouldBe ConfigParser.parseConfString(s"""
        |param1=yyy
        |""".stripMargin
      )
      // and entries should preserve the update from latestConf, even though it hasn't changed from previousConf
      entries shouldBe Map(
        "param1" -> s"yyy"
      )
    }
  }

  def dependencyConfig(configs: Map[String, String]) =
    DependencyConfig(
      group    = "g",
      artefact = "a",
      version  = "v",
      configs  = configs
    )
}
