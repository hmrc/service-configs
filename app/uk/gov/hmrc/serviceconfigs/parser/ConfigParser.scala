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

import com.typesafe.config.{Config, ConfigFactory, ConfigIncludeContext, ConfigIncluder, ConfigObject, ConfigParseOptions, ConfigSyntax}
import javax.inject.Singleton
import org.yaml.snakeyaml.Yaml

import scala.collection.convert.decorateAsScala._
import scala.util.Try

@Singleton
class ConfigParser {

  def parseConfStringAsMap(confString: String): Option[Map[String, String]] = {
    val fallbackIncluder = ConfigParseOptions.defaults.getIncluder

    val doNotInclude = new ConfigIncluder() {
      override def withFallback(fallback: ConfigIncluder): ConfigIncluder             = this
      override def include(context: ConfigIncludeContext, what: String): ConfigObject =
        //        ConfigFactory.parseString(what).root()
        ConfigFactory.empty.root()
    }

    val options: ConfigParseOptions =
      ConfigParseOptions
        .defaults
        .setSyntax(ConfigSyntax.CONF)
        .setAllowMissing(false)
        .setIncluder(doNotInclude)

    if (confString.isEmpty) None
    else Try(ConfigFactory.parseString(confString, options))
          .map(flattenConfigToDotNotation)
          .toOption
  }

  def parseYamlStringAsMap(yamlString: String): Option[Map[String, String]] =
    Try(new Yaml().load(yamlString).asInstanceOf[java.util.LinkedHashMap[String, Object]])
      .map(flattenYamlToDotNotation)
      .toOption


  private def flattenConfigToDotNotation(input : Config): Map[String, String] =
    input.entrySet.asScala
      .map(e => s"${e.getKey}" -> removeQuotes(e.getValue.render))
      .toMap

  private def removeQuotes(input: String): String =
    if (input.charAt(0).equals('"') && input.charAt(input.length - 1).equals('"')) {
      input.substring(1, input.length - 1)
    } else {
      input
    }

  private def flattenYamlToDotNotation(input: java.util.LinkedHashMap[String, Object]): Map[String, String] = {
    def go(input: Map[String, Object], currentPrefix: String): Map[String, String] =
      input.flatMap {
        case (k: String, v: java.util.LinkedHashMap[String, Object]) =>
          go(v.asScala.toMap, buildPrefix(currentPrefix, k))
        case (k: String, v: Object) =>
          Map(buildPrefix(currentPrefix, k) -> v.toString)
      }
    go(input.asScala.toMap, "")
  }

  private def buildPrefix(currentPrefix: String, key: String) =
    (currentPrefix, key) match {
      case ("", "0.0.0") => "" // filter out the (unused) config version numbering
      case ("", k      ) => k
      case (cp, k      ) => s"$cp.$k"
    }
}
