/*
 * Copyright 2020 HM Revenue & Customs
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

import com.typesafe.config.{
  Config,
  ConfigFactory,
  ConfigIncludeContext,
  ConfigIncluder,
  ConfigIncluderClasspath,
  ConfigObject,
  ConfigParseOptions,
  ConfigRenderOptions,
  ConfigSyntax
}
import org.yaml.snakeyaml.Yaml
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.model.DependencyConfig
import scala.collection.convert.decorateAsScala._
import scala.util.Try

trait ConfigParser {

  def parseConfString(confString: String,
                      includeCandidates: Map[String, String] = Map.empty,
                      logMissing: Boolean = true): Config = {
    val includer = new ConfigIncluder with ConfigIncluderClasspath {
      val exts = List(".conf", ".json", ".properties") // however service-dependencies only includes .conf files (should we extract the others too since they could be used?)
      override def withFallback(fallback: ConfigIncluder): ConfigIncluder = this
      override def include(context: ConfigIncludeContext,
                           what: String): ConfigObject =
        includeResources(context, what)

      override def includeResources(context: ConfigIncludeContext,
                                    what: String): ConfigObject = {
        includeCandidates.find {
          case (k, v) =>
            if (exts.exists(ext => what.endsWith(ext))) k == what
            else exts.exists(ext => k == s"$what$ext")
        } match {
          case Some((_, v)) =>
            ConfigFactory.parseString(v, context.parseOptions).root
          case None =>
            if (logMissing)
              Logger.warn(
                s"Could not find $what to include in $includeCandidates"
              )
            ConfigFactory.empty.root
        }
      }
    }

    val parseOptions: ConfigParseOptions =
      ConfigParseOptions.defaults
        .setSyntax(ConfigSyntax.CONF)
        .setIncluder(includer)

    ConfigFactory.parseString(confString, parseOptions)
  }

  def parseYamlStringAsMap(yamlString: String): Option[Map[String, String]] =
    Try(
      new Yaml()
        .load(yamlString)
        .asInstanceOf[java.util.LinkedHashMap[String, Object]]
    ).map(flattenYamlToDotNotation)
      .toOption

  def flattenConfigToDotNotation(config: Config): Map[String, String] =
    Try(config.entrySet)
    // Some configs try to replace unresolved subsitutions - resolve them first
      .orElse(Try(config.resolve.entrySet))
      // However some configs cannot be resolved since are provided by later overrides
      .getOrElse(ConfigFactory.empty.entrySet)
      .asScala
      .map(
        e =>
          s"${e.getKey}" -> removeQuotes(
            e.getValue.render(ConfigRenderOptions.concise)
        )
      )
      .toMap

  private def removeQuotes(input: String): String =
    if (input
          .charAt(0)
          .equals('"') && input.charAt(input.length - 1).equals('"'))
      input.substring(1, input.length - 1)
    else
      input

  private def flattenYamlToDotNotation(
    input: java.util.LinkedHashMap[String, Object]
  ): Map[String, String] = {
    def go(input: Map[String, Object],
           currentPrefix: String): Map[String, String] =
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
      case ("", "0.0.0") =>
        "" // filter out the (unused) config version numbering
      case ("", k) => k
      case (cp, k) => s"$cp.$k"
    }

  def toIncludeCandidates(
    dependencyConfigs: Seq[DependencyConfig]
  ): Map[String, String] =
    // first include file takes precedence
    dependencyConfigs.foldRight(Map.empty[String, String])(
      (c, m) => m ++ c.configs
    )

  /** Combine the reference.conf and play/reference-overrides.conf configs according order,
    * respecting any include directives.
    */
  def reduceConfigs(dependencyConfigs: Seq[DependencyConfig]): Config =
    dependencyConfigs.tails
      .map {
        case dc :: rest =>
          def configFor(filename: String) =
            dc.configs
              .get(filename)
              .map(parseConfString(_, toIncludeCandidates(dc :: rest)))
              .getOrElse(ConfigFactory.empty)
          configFor("play/reference-overrides.conf").withFallback(
            configFor("reference.conf")
          )
        case _ => ConfigFactory.empty
      }
      .reduceLeft(_ withFallback _)
}

object ConfigParser extends ConfigParser
