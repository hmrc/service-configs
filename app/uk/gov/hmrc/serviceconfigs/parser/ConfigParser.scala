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

import com.typesafe.config.{Config, ConfigFactory, ConfigIncludeContext, ConfigIncluder, ConfigObject, ConfigParseOptions, ConfigRenderOptions, ConfigSyntax}
import javax.inject.Singleton
import org.yaml.snakeyaml.Yaml
import play.api.Logger
import uk.gov.hmrc.cataloguefrontend.connector.DependencyConfig

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
      .map(e => s"${e.getKey}" -> removeQuotes(e.getValue.render(com.typesafe.config.ConfigRenderOptions.concise)))
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

  /** Combine the reference.conf and play/reference-overrides.conf configs according to classpath order,
    * respecting any include directives.
    */
  def reduceConfigs(dependencyConfigs: Seq[DependencyConfig]): String = {
   val config2s =
     dependencyConfigs.flatMap { dependencyConfigs =>
       dependencyConfigs.configs
         .map { case (filename, content) => Config2(filename, content) }
         .toList
         // ensure for a given dependency, ordered by play/reference-overrides.conf -> reference.conf -> others (for including)
         .sortWith((l, r) =>  l.filename == "play/reference-overrides.conf"
                          || (l.filename == "reference.conf" && r.filename != "play/reference-overrides.conf"))
     }
    config2String(combineConfigs(config2s))
  }

  private val includeRegex = "(?m)^include \"(.*)\"$".r

  /** recursively applies includes by inlining
    */
  def applyIncludes(config: Config2, includeCandidates: Seq[Config2]): String = {
    val includes = includeRegex.findAllMatchIn(config.content).map(_.group(1)).toList
    includes.foldLeft(config.content){ (acc, include) =>
      val includeContent = includeCandidates
        .tails
        .flatMap {
          case includeCandidate :: rest if List(include, s"$include.conf").contains(includeCandidate.filename) =>
              Some(applyIncludes(includeCandidate, rest))
          case _ => None
        }
        .toList
        .headOption
        .getOrElse { Logger.warn(s"include `$include` not found"); ""}
      Logger.debug(s"replacing include with $includeContent")
      acc.replace(s"""include "$include"""", includeContent)
    }
  }

  def config2String(config: Config): String =
    config.root.render(ConfigRenderOptions.concise)

  def combineConfigs(orderedConfigs: Seq[Config2]) =
    orderedConfigs
      .tails
      .map {
        case config :: rest if List("reference.conf", "play/reference-overrides.conf").contains(config.filename) =>
          val content = applyIncludes(config, rest)
          ConfigFactory.parseString(content)
        case _ => ConfigFactory.empty
      }
      .reduceLeft(_ withFallback _)

  /** Combine the configs according to classpath order.
    *
    * @return (slugConfig, applicationConfig, referenceConfig)
    * where slugConfig is the <slugname>.conf (and any includes) excluding referenceConfig,
    *       applicationConfig is the application.conf (and any includes) excluding referenceConfig,
    *       referenceConfig is the reference.conf (and play/reference-overrides.conf) combined (and any includes).
    */
/*  def reduceConfigs(classpath: String, configs: Seq[Config]): (String, String, String) = {
    // TODO refactor this - relies on knowledge that first two look like... (just confirm it's true and bomb if not?)
    // <Config path=../conf/ filename=service-dependencies.conf>
    // <Config path=../conf/ filename=application.conf>
    // <...>
    val (slugConf :: appConf :: unorderedReferenceAndIncludes) = configs
    val referenceAndIncludes = orderConfigs(classpath, unorderedReferenceAndIncludes)
    val slugConfig        = ConfigFactory.parseString(applyIncludes(slugConf, appConf :: referenceAndIncludes))
    val applicationConfig = ConfigFactory.parseString(applyIncludes(appConf, referenceAndIncludes))
    val referenceConfig   = combineConfigs(referenceAndIncludes)
    ( config2String(slugConfig)
    , config2String(applicationConfig)
    , config2String(referenceConfig)
    )
  }
  */
}


case class Config2(
    filename: String
  , content : String
  ) {
    override def toString: String = s"<Config filename=$filename>"
  }
