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

import java.util

import com.typesafe.config._
import javax.inject.Singleton
import org.yaml.snakeyaml.Yaml

import scala.collection.mutable
import scala.util.Try

@Singleton
class ConfigParser {

  def loadConfResponseToMap(responseString: String): Option[Map[String, String]] = {
    import scala.collection.mutable.Map

    val fallbackIncluder = ConfigParseOptions.defaults().getIncluder()

    val doNotInclude = new ConfigIncluder() {
      override def withFallback(fallback: ConfigIncluder): ConfigIncluder             = this
      override def include(context: ConfigIncludeContext, what: String): ConfigObject =
        //        ConfigFactory.parseString(what).root()
        ConfigFactory.empty.root()
    }

    val options: ConfigParseOptions =
      ConfigParseOptions.defaults().setSyntax(ConfigSyntax.CONF).setAllowMissing(false).setIncluder(doNotInclude)
    responseString match {
      case s: String if s.nonEmpty =>
        Try(ConfigFactory.parseString(responseString, options))
          .map(flattenConfigToDotNotation)
          .toOption
      case _ => None
    }
  }

  def loadYamlResponseToMap(responseString: String): Option[Map[String, String]] = {
    import scala.collection.convert.decorateAsScala._
    //import scala.collection.JavaConversions.mapAsScalaMap
    responseString match {
      case s: String if s.nonEmpty =>
        Try(new Yaml().load(responseString).asInstanceOf[util.LinkedHashMap[String, Object]].asScala.toMap)
          .map(flattenYamlToDotNotation)
          .toOption
      case _ => None
    }
  }


  private def flattenConfigToDotNotation(input : Config): Map[String, String] = {
    def flattenConfigToDotNotation2(
        start : mutable.Map[String, String],
        input : Config,
        prefix: String = ""): mutable.Map[String, String] = {
      input.entrySet().toArray().foreach {
        case e: java.util.AbstractMap.SimpleImmutableEntry[Object, com.typesafe.config.ConfigValue] =>
          start.put(s"${e.getKey.toString}", removeQuotes(e.getValue.render))
        case e => println("Can't do that!")
      }
      start
    }
    flattenConfigToDotNotation2(start = mutable.Map(), input).toMap
  }

  private def removeQuotes(input: String) =
    if (input.charAt(0).equals('"') && input.charAt(input.length - 1).equals('"')) {
      input.substring(1, input.length - 1)
    } else {
      input
    }

  private def flattenYamlToDotNotation(input : Map[String, Object]): Map[String, String] = {
    def flattenYamlToDotNotation2(
        start        : mutable.Map[String, String],
        input        : mutable.Map[String, Object],
        currentPrefix: String = ""): mutable.Map[String, String] = {
      import scala.collection.JavaConversions.mapAsScalaMap
      input foreach {
        case (k: String, v: mutable.Map[String, Object]) =>
          flattenYamlToDotNotation2(start, v, buildPrefix(currentPrefix, k))
        case (k: String, v: java.util.LinkedHashMap[String, Object]) =>
          flattenYamlToDotNotation2(start, v, buildPrefix(currentPrefix, k))
        case (k: String, v: Object) => start.put(buildPrefix(currentPrefix, k), v.toString)
        case _ =>
      }
      start
    }
    flattenYamlToDotNotation2(start = mutable.Map(), mutable.Map(input.toSeq: _*)).toMap
  }

  private def buildPrefix(currentPrefix: String, key: String) =
    (currentPrefix, key) match {
      case ("", "0.0.0")         => currentPrefix // filter out the (unused) config version numbering
      case (cp, _) if cp.isEmpty => key
      case _                     => s"$currentPrefix.$key"
    }
}
