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

import com.typesafe.config.{ConfigValue => TSConfigValue, ConfigValueType => TSConfigValueType, _}
import org.yaml.snakeyaml.Yaml
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.model.DependencyConfig
import uk.gov.hmrc.serviceconfigs.util.SafeXml

import java.util.Properties
import scala.jdk.CollectionConverters._
import scala.util.Try

trait ConfigParser extends Logging:

  def parseConfString(
    confString       : String,
    includeCandidates: Map[String, String] = Map.empty,
    logMissing       : Boolean             = true
  ): Config =
    val includer = new ConfigIncluder with ConfigIncluderClasspath:
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
        includeCandidates.find:
          case (k, v) =>
            if exts.exists(ext => what.endsWith(ext)) then
              k == what
            else
              exts.exists(ext => k == s"$what$ext")
        match
          case Some((_, v)) => ConfigFactory.parseString(v, context.parseOptions).root
          case None         => if logMissing then logger.warn(s"Could not find $what to include in ${includeCandidates.keys}")
                               ConfigFactory.empty.root

    val parseOptions: ConfigParseOptions =
      ConfigParseOptions
        .defaults
        .setSyntax(ConfigSyntax.CONF)
        .setIncluder(includer)

    ConfigFactory.parseString(confString, parseOptions)

  // We return as Properties rather than Map[String, String] since
  // they are treated differently when converting to Config - i.e.
  // Map will fail when merging Object and Value - where as Properties will not, since it has a defined ordering.
  // Also note, that for Properties, the object takes precendence, where as for String (also with defined ordering), the second entry will.
  // Since Yaml is provided as Properties to the service, we treat in the same way here.
  def parseYamlStringAsProperties(yamlString: String): Properties =
    val props = Properties()
    Try(
      Yaml()
        .load(yamlString)
        .asInstanceOf[java.util.LinkedHashMap[String, Object]]
    ).map(flattenYamlToDotNotation)
      .getOrElse(Seq.empty)
      .foreach:
        case (k, v) => props.setProperty(k, v)
    props

  def parseXmlLoggerConfigStringAsMap(xmlString: String): Option[Map[String, ConfigValue]] =
    Try:
      val xml = SafeXml().loadString(xmlString)
      val root =
        (for
           node  <- (xml  \ "root"  ).headOption
           level <- (node \ "@level").headOption
         yield Map("logger.root" -> ConfigValue(level.text))
        ).getOrElse(Map.empty)
      val logger =
        (xml \ "logger")
          .flatMap: x =>
            for
              k          <- (x \ "@name" ).headOption
              v          <- (x \ "@level").headOption
              loggerName =  if (k.text.contains(".")) s"\"${k.text}\"" else k.text
            yield s"logger.$loggerName" -> ConfigValue(v.text)
          .toMap
      root ++ logger
    .toOption

  /** The config is resolved (substitutions applied) and
    * the config is returned as a flat Map
    */
  def flattenConfigToDotNotation(config: Config): Map[String, ConfigValue] =
    entrySetWithNull(
      config.resolve(
        ConfigResolveOptions
          .defaults
          .setAllowUnresolved(true)
          .setUseSystemEnvironment(false) //environment substitutions cannot be resolved
        )
    )
    .toMap

  // replicating https://github.com/lightbend/config/blob/5d6a46631c7ca1d617de02459bd62af5b875073b/config/src/main/java/com/typesafe/config/impl/Path.java#L178
  private def hasFunkyChars(c: Char) =
    !(Character.isLetterOrDigit(c) || c == '-' || c == '_')

  private case class Acc(
    acc         : Seq[(String, ConfigValue)],
    configObject: ConfigObject,
    path        : String
  )

  /** calling config.entrySet will strip out keys with null values */
  def entrySetWithNull(config: Config): Set[(String, ConfigValue)] =
    (summon[cats.Monad[Seq]].tailRecM[Acc, (String, ConfigValue)](
      Acc(acc = Seq.empty[(String, ConfigValue)], configObject = config.root(), path = "")
    ):
      case Acc(acc, configObject, path) =>
        configObject.entrySet().asScala.toSeq.flatMap: e =>
          val key =
            path +
            (if (path.nonEmpty) "." else "") +
            (if (e.getKey.exists(hasFunkyChars)) s"\"${e.getKey}\"" else e.getKey)

          e.getValue match
            case o: ConfigObject => Seq(Left(Acc(acc = acc, o, key)))
            case other           => (acc ++ Seq(key ->
                                      // `Try` to avoid `substitution not resolved: ConfigConcatenation(${play.server.dir}"/RUNNING_PID"`
                                      (
                                        if Try(config.getIsNull(key)).getOrElse(false) then ConfigValue.Null
                                        else                                                ConfigValue.apply(e.getValue)
                                      )
                                    )).map(Right.apply)
    ).toSet

  private def flattenYamlToDotNotation(
    input: java.util.LinkedHashMap[String, Object]
  ): Seq[(String, String)] =
    def go(
      input        : Seq[(String, Object)],
      currentPrefix: String
    ): Seq[(String, String)] =
      input.flatMap:
        case (k: String, v: java.util.LinkedHashMap[String, Object]) =>
          go(v.asScala.toSeq, buildPrefix(currentPrefix, k))
        case (k: String, v: Object) =>
          Seq(buildPrefix(currentPrefix, k) -> v.toString)
    go(input.asScala.toSeq, "")

  private def buildPrefix(currentPrefix: String, key: String) =
    (currentPrefix, key) match
      case ("", "0.0.0") =>
        "" // filter out the (unused) config version numbering
      case ("", k) => k
      case (cp, k) => s"$cp.$k"

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
    dependencyConfigs
      .tails
      .map:
        case dc :: rest =>
          def configFor(filename: String) =
            dc.configs
              .get(filename)
              .map(parseConfString(_, toIncludeCandidates(dc :: rest)))
              .getOrElse(ConfigFactory.empty)
          configFor("play/reference-overrides.conf")
            .withFallback(configFor("reference.conf"))
        case _ => ConfigFactory.empty
      .reduceLeft(_ withFallback _)

  def extractAsConfig(properties: Properties, prefix: String): (Config, Map[String, ConfigValue]) =
    val newProps = Properties()

    properties
      .entrySet
      .asScala
      .foreach: e =>
        if e.getKey.toString.startsWith(prefix) then
          newProps.setProperty(e.getKey.toString.replace(prefix, ""), e.getValue.toString)

    val config = ConfigFactory.parseProperties(newProps)

    // logger is actually loaded from System.properties, and preserves the `.` within the String rather as a subpath
    val configWithPreservedLogger = config
      .withoutPath("logger")
      .withFallback(
        ConfigFactory.parseMap(
          newProps.entrySet.asScala
            .map(e => (e.getKey.toString, e.getValue.toString))
            .collect:
              case (k, v) if k.startsWith("logger.") =>
                k.stripPrefix("logger.") match
                  case "resource" | "json.dateformat" => k -> v
                  case loggerPath                     => s"logger.\"$loggerPath\"" -> v
            .toMap.asJava
        )
      )

    val suppressed = newProps.asScala.view
                       .filterKeys(!flattenConfigToDotNotation(configWithPreservedLogger).contains(_))
                       .mapValues(ConfigValue.apply)
                       .toMap
    (configWithPreservedLogger, suppressed)

  /** Config is processed relative to the previous one.
    * The accumulative config (unresolved) is returned along with a Map contining the effective changes -
    * i.e. new entries from latestConf, or entries that have been changed because of latestConf (e.g. latestConf provided different substitutions)
    */
  def delta(latestConf: Config, previousConf: Config): (Config, Map[String, ConfigValue]) =
    val previousConfMap = ConfigParser.flattenConfigToDotNotation(previousConf)

    val conf = latestConf.withFallback(previousConf)
    val latestConfResolved = // we have to resolve in order to call "hasPath"
      latestConf.resolve(
        ConfigResolveOptions.defaults
          .setAllowUnresolved(true)
          .setUseSystemEnvironment(false)
      )
    val confAsMap  = ConfigParser.flattenConfigToDotNotation(conf).view
    val confAsMap2 = confAsMap.foldLeft(Map.empty[String, ConfigValue]):
      case (acc, (k, v)) =>
        // some entries cannot be resolved. e.g. `play.server.pidfile.path -> ${play.server.dir}"/RUNNING_PID"`
        // keep it for now...
        if previousConfMap.get(k).fold(true)(_.asString != v.asString) || Try(latestConfResolved.hasPath(k)).getOrElse(true) then
          acc ++ Seq(k -> v) // and not explicitly included in previousConf
        else
          acc
    (conf, confAsMap2)

  /** Returns keys (and values) in previousConfig that have been removed by the application of the latestConfig.
    * This is often the sign of an error.
    */
  def suppressed(latestConf: Config, optPreviousConf: Option[Config]): Map[String, ConfigValue] =
    val previousConf = optPreviousConf.getOrElse(ConfigFactory.empty)
    val combined     = flattenConfigToDotNotation(latestConf.withFallback(previousConf))
    flattenConfigToDotNotation(previousConf)
      .view
      .filterKeys(!combined.contains(_))
      .toMap

object ConfigParser extends ConfigParser

case class ConfigValue(
  asString : String,
  valueType: ConfigValueType
)

object ConfigValue:
  val Null: ConfigValue =
    ConfigValue(asString = "<<NULL>>", ConfigValueType.Null)

  val Suppressed: ConfigValue =
    ConfigValue(asString = "<<SUPPRESSED>>", ConfigValueType.Suppressed)

  def apply(s: String): ConfigValue =
    ConfigValue(asString = s, valueType = ConfigValueType.SimpleValue)

  def apply(value: TSConfigValue): ConfigValue =
    // TODO should we also store value.origin (filename, lineNumber etc.)
    ConfigValue(
      asString  = suppressEncryption(removeQuotes(value.render(ConfigRenderOptions.concise))),
      valueType = Try(
                    value.valueType match
                      case TSConfigValueType.LIST   => ConfigValueType.List
                      case TSConfigValueType.OBJECT => ConfigValueType.Object
                      case _                        => ConfigValueType.SimpleValue
                  ).getOrElse(ConfigValueType.Unmerged)
    )

  private def removeQuotes(input: String): String =
    if input.charAt(0).equals('"') && input.charAt(input.length - 1).equals('"') then
      input.substring(1, input.length - 1)
    else
      input

  private val encryptionRegex = raw"ENC\[([^]]*)]".r
  // <encType>,<UUID>,<encryptedValue>
  private val secretIdExtractor = raw"(\w*),([a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}),(.*)".r
  def suppressEncryption(input: String): String =
    encryptionRegex.replaceAllIn(
      input
    , _.group(1) match
        case secretIdExtractor(a, id, b) => s"ENC[$id]" // preserving the id will help us identify when secrets have changed
        case _                           => s"ENC[...]"
    )

// We don't use TSConfigValueType since distinguishing between BOOLEAN, NUMBER and STRING doesn't work with System.properties
// and we want to add Unmerged, when we can't tell (e.g. placeholders need resolving)
enum ConfigValueType:
  case Null
  case Unmerged
  case SimpleValue
  case List
  case Object
  case Suppressed
