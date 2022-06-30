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

import com.typesafe.config._
import org.yaml.snakeyaml.Yaml
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.model.DependencyConfig
import uk.gov.hmrc.serviceconfigs.util.SafeXml

import java.util.Properties
import scala.jdk.CollectionConverters._
import scala.util.Try

trait ConfigParser extends Logging {

  def parseConfString(
    confString       : String,
    includeCandidates: Map[String, String] = Map.empty,
    logMissing       : Boolean             = true
  ): Config = {
    val includer = new ConfigIncluder with ConfigIncluderClasspath {
      val exts = List(".conf", ".json", ".properties") // however service-dependencies only includes .conf files (should we extract the others too since they could be used?)
      override def withFallback(fallback: ConfigIncluder): ConfigIncluder = this
      override def include(
        context: ConfigIncludeContext,
        what   : String
      ): ConfigObject =
        includeResources(context, what)

      override def includeResources(
        context: ConfigIncludeContext,
        what   : String
      ): ConfigObject =
        includeCandidates.find { case (k, v) =>
          if (exts.exists(ext => what.endsWith(ext)))
            k == what
          else
            exts.exists(ext => k == s"$what$ext")
        } match {
          case Some((_, v)) => ConfigFactory.parseString(v, context.parseOptions).root
          case None         => if (logMissing)
                                 logger.warn(s"Could not find $what to include in $includeCandidates")
                               ConfigFactory.empty.root
        }
    }

    val parseOptions: ConfigParseOptions =
      ConfigParseOptions.defaults
        .setSyntax(ConfigSyntax.CONF)
        .setIncluder(includer)

    ConfigFactory.parseString(confString, parseOptions)
  }

  // we return as Seq[(String, String)] rather than Map[String, String]
  def parseYamlStringAsSeq(yamlString: String): Option[Seq[(String, String)]] =
    Try(
      new Yaml()
        .load(yamlString)
        .asInstanceOf[java.util.LinkedHashMap[String, Object]]
    ).map(flattenYamlToDotNotation)
      .toOption

  def parseXmlLoggerConfigStringAsMap(xmlString: String): Option[Map[String, String]] =
    Try {
      val xml = SafeXml().loadString(xmlString)
      val root =
        (for {
           node  <- (xml  \ "root"  ).headOption
           level <- (node \ "@level").headOption
         } yield Map("logger.root" -> level.text)
        ).getOrElse(Map.empty)
      val logger =
          (xml \ "logger")
            .flatMap(x =>
              for {
                k <- (x \ "@name" ).headOption
                v <- (x \ "@level").headOption
              } yield s"logger.${k.text}" -> v.text
            )
            .toMap
      root ++ logger
    }.toOption

  /** The config is resolved (substitutions applied) and
    * the config is returned as a flat Map
    */
  def flattenConfigToDotNotation(config: Config): Map[String, String] =
    config
      .resolve(
        ConfigResolveOptions.defaults
          .setAllowUnresolved(true)
          .setUseSystemEnvironment(false) //environment substitutions cannot be resolved
        )
      .entrySet
      .asScala
      .map(e =>
        s"${e.getKey}" -> removeQuotes(e.getValue.render(ConfigRenderOptions.concise))
      )
      .toMap

  private def removeQuotes(input: String): String =
    if (input.charAt(0).equals('"') &&
         input.charAt(input.length - 1).equals('"'))
      input.substring(1, input.length - 1)
    else
      input

  private def flattenYamlToDotNotation(
    input: java.util.LinkedHashMap[String, Object]
  ): Seq[(String, String)] = {
    def go(
      input        : Seq[(String, Object)],
      currentPrefix: String
    ): Seq[(String, String)] =
      input.flatMap {
        case (k: String, v: java.util.LinkedHashMap[String, Object]) =>
          go(v.asScala.toSeq, buildPrefix(currentPrefix, k))
        case (k: String, v: Object) =>
          Seq(buildPrefix(currentPrefix, k) -> v.toString)
      }
    go(input.asScala.toSeq, "")
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

  def extractAsConfig(config: Seq[(String, String)], prefix: String): Config =
    // ConfigFactory.parseMap will fail if there are parent and child configs, since it can't merge object and value
    // Both parseProperties and parseString don't have that problem since there is a defined order - howeever they treat it differently
    // for parseProperties the object takes precedence, for parseString the second entry will.
    // We parse with parseProperties here since this is how the config is provided to the service

    // TODO provide the config as Properties, rather than Seq
    // TODO collect a list of ignored entries and return to client (e.g. key -> "IGNORED!")
    ConfigFactory.parseProperties {
      val p = new Properties
      config
        .view
        .filter(_._1.startsWith(prefix))
        .map { case (k, v) => k.replace(prefix, "") -> v }
        .foreach { case (k, v) => p.setProperty(k, v) }
      p
    }

  /** Config is processed relative to the previous one.
    * The accumulative config (unresolved) is returned along with a Map contining the effective changes -
    * i.e. new entries from latestConf, or entries that have been changed because of latestConf (e.g. latestConf provided different substitutions)
    */
  def delta(latestConf: Config, previousConf: Config): (Config, Map[String, String]) = {
    val previousConfMap = ConfigParser.flattenConfigToDotNotation(previousConf)

    val conf = latestConf.withFallback(previousConf)
    val latestConfResolved = // we have to resolve in order to call "hasPath"
      latestConf.resolve(
        ConfigResolveOptions.defaults
          .setAllowUnresolved(true)
          .setUseSystemEnvironment(false)
      )
    val confAsMap = ConfigParser.flattenConfigToDotNotation(conf)
    val confAsMap2 = confAsMap.foldLeft(Map.empty[String, String]){ case (acc, (k, v)) =>
        // some entries cannot be resolved. e.g. `play.server.pidfile.path -> ${play.server.dir}"/RUNNING_PID"`
        // keep it for now...
        if (previousConfMap.get(k) != Some(v) ||
          scala.util.Try(latestConfResolved.hasPath(k)).getOrElse(true)
        )
          acc ++ Seq(k -> v) // and not explicitly included in previousConf
        else
          acc
      }
    (conf, confAsMap2)
  }
}

object ConfigParser extends ConfigParser
