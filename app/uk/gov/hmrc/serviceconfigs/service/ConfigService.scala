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

package uk.gov.hmrc.serviceconfigs.service

import cats.instances.all._
import cats.syntax.all._
import com.typesafe.config.Config
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.ConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, Environment, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.serviceconfigs.parser.ConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository}

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

@Singleton
class ConfigService @Inject()(
  configConnector           : ConfigConnector,
  slugInfoRepository        : SlugInfoRepository,
  dependencyConfigRepository: DependencyConfigRepository
)(implicit ec: ExecutionContext) {

  import ConfigService._

  val environments: Seq[EnvironmentMapping] = Seq(
    EnvironmentMapping("local"       , SlugInfoFlag.Latest                                  ),
    EnvironmentMapping("development" , SlugInfoFlag.ForEnvironment(Environment.Development )),
    EnvironmentMapping("qa"          , SlugInfoFlag.ForEnvironment(Environment.QA          )),
    EnvironmentMapping("staging"     , SlugInfoFlag.ForEnvironment(Environment.Staging     )),
    EnvironmentMapping("integration" , SlugInfoFlag.ForEnvironment(Environment.Integration )),
    EnvironmentMapping("externaltest", SlugInfoFlag.ForEnvironment(Environment.ExternalTest)),
    EnvironmentMapping("production"  , SlugInfoFlag.ForEnvironment(Environment.Production  ))
  )

  private def lookupLoggerConfig(optSlugInfo: Option[SlugInfo]): Map[String, String] =
    optSlugInfo match {
      // LoggerModule was added for this version
      case Some(slugInfo) if slugInfo.dependencies.exists(d =>
                            d.group == "uk.gov.hmrc"
                            && List("bootstrap-frontend-play-28", "bootstrap-backend-play-28").contains(d.artifact)
                            && Version.parse(d.version).exists(_ >= Version("5.18.0"))
                          ) =>
        ConfigParser
          .parseXmlLoggerConfigStringAsMap(slugInfo.loggerConfig)
          .getOrElse(Map.empty)
      case _ => Map.empty[String, String]
    }

  private def lookupDependencyConfigs(optSlugInfo: Option[SlugInfo]): Future[List[DependencyConfig]] =
    optSlugInfo match {
      case Some(slugInfo) =>
        slugInfo.dependencies.foldLeftM(List.empty[DependencyConfig]){ case (acc, d) =>
          dependencyConfigRepository.getDependencyConfig(d.group, d.artifact, d.version)
            .map(acc ++ _)
        }
      case None => Future.successful(List.empty[DependencyConfig])
    }

  /** Gets application config from slug info or config connector (Java apps).
    * Exposes the raw bootstrap conf if its at the start of the file, otherwise merges it in.
    */
  private def lookupApplicationConf(
    serviceName     : String,
    referenceConfigs: List[DependencyConfig],
    optSlugInfo     : Option[SlugInfo]
  )(implicit hc: HeaderCarrier): Future[(Config, Map[String, Config])] =
    for {
      applicationConfRaw <- optSlugInfo.traverse {
                              // if no slug info (e.g. java apps) get from github
                              case x if x.applicationConfig == "" => configConnector.serviceApplicationConfigFile(serviceName)
                              case x                              => Future.successful(Some(x.applicationConfig))
                            }.map(_.flatten.getOrElse(""))
      regex              =  """^include\s+["'](frontend.conf|backend.conf)["']""".r.unanchored
      optBootstrapFile   =  applicationConfRaw
                              .split("\n")
                              .filterNot(_.trim.startsWith("#"))
                              .mkString("\n")
                              .trim match {
                                case regex(v) => Some(v)
                                case _        => None
                              }
      bootstrapConf      =  referenceConfigs
                              .flatMap(_.configs)
                              .collect {
                                case ("frontend.conf", v) if optBootstrapFile.exists(_ == "frontend.conf") => "bootstrapFrontendConf" -> ConfigParser.parseConfString(v)
                                case ("backend.conf", v)  if optBootstrapFile.exists(_ == "backend.conf")  => "bootstrapBackendConf"  -> ConfigParser.parseConfString(v)
                              }
                              .toMap
      includeConfs       =  referenceConfigs
                              .map(x => x.copy(configs = optBootstrapFile.foldLeft(x.configs)((c, y) => c + (y -> ""))))
      applicationConf    =  ConfigParser.parseConfString(applicationConfRaw, ConfigParser.toIncludeCandidates(includeConfs))
    } yield
      (applicationConf, bootstrapConf)

  private def lookupBaseConf(
    serviceName: String,
    optSlugInfo: Option[SlugInfo]
  )(implicit hc: HeaderCarrier): Future[Config] =
    for {
      optBaseConfRaw <- optSlugInfo match {
                          // if no slug config (e.g. java apps) get from github
                          case Some(x) if x.slugConfig == "" => configConnector.serviceConfigBaseConf(serviceName)
                          case Some(x)                       => Future.successful(Some(x.slugConfig))
                          case None                          => Future.successful(None)
                        }
    } yield
      ConfigParser.parseConfString(optBaseConfRaw.getOrElse(""), logMissing = false) // ignoring includes, since we know this is applicationConf

  /** Converts the unresolved configurations for each level into a
    * list of the effective configs
    */
  private def toConfigSourceEntries(cscs: Seq[ConfigSourceConfig]): Seq[ConfigSourceEntries] = {
    val (cses, lastConfig) = cscs.foldLeft((Seq.empty[ConfigSourceEntries], None: Option[Config])){ case ((acc, optPreviousConfig), entry) =>
      val (nextConfig, entries) = optPreviousConfig match {
          case None                 => (entry.config, ConfigParser.flattenConfigToDotNotation(entry.config))
          case Some(previousConfig) => ConfigParser.delta(entry.config, previousConfig)
      }
      val suppressed = (ConfigParser.suppressed(entry.config, optPreviousConfig) ++ entry.suppressed)
                        .map { case (k, _) => k -> s"<<SUPPRESSED>>" }
      (acc :+ ConfigSourceEntries(entry.name, entries ++ suppressed), Some(nextConfig))
    }
    // ApplicationLoader in bootstrap will decode any ".base64" keys and replace the keys without the .base64 extension
    lastConfig match {
      case Some(config) =>
        val base64 = ConfigParser.flattenConfigToDotNotation(config).flatMap {
          case (k, v) if k.endsWith(".base64") => Map(k.replaceAll("\\.base64$", "") -> Try(new String(Base64.getDecoder.decode(v), "UTF-8")).getOrElse("<<Invalid base64>>"))
          case _                               => Map.empty
        }
        cses :+ ConfigSourceEntries("base64", base64)
      case None =>
        cses
    }
  }

  private def configSourceEntries(
    environment: EnvironmentMapping,
    serviceName: String
  )(implicit
    hc: HeaderCarrier
  ): Future[Seq[ConfigSourceEntries]] =
    if (environment.name == "local")
      for {
        optSlugInfo               <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)

        dependencyConfigs         <- lookupDependencyConfigs(optSlugInfo)
        referenceConf             =  ConfigParser.reduceConfigs(dependencyConfigs)

        (applicationConf, bootstrapConf)
                                  <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)
      } yield toConfigSourceEntries(
        ConfigSourceConfig("referenceConf"  , referenceConf  , Map.empty)               ::
        bootstrapConf.map { case (k, v) => ConfigSourceConfig(k, v, Map.empty) }.toList :::
        ConfigSourceConfig("applicationConf", applicationConf, Map.empty) ::
        Nil
      )
    else
    for {
      optSlugInfo                 <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)

      loggerConfMap               =  lookupLoggerConfig(optSlugInfo)

      dependencyConfigs           <- lookupDependencyConfigs(optSlugInfo)
      referenceConf               =  ConfigParser.reduceConfigs(dependencyConfigs)

      (applicationConf, bootstrapConf)
                                  <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)

      optAppConfigEnvRaw          <- configConnector.serviceConfigYaml(environment.slugInfoFlag, serviceName)
      appConfigEnvEntriesAll      =  ConfigParser
                                       .parseYamlStringAsProperties(optAppConfigEnvRaw.getOrElse(""))
      serviceType                 =  appConfigEnvEntriesAll.entrySet.asScala.find(_.getKey == "type").map(_.getValue.toString)
      (appConfigEnvironment, appConfigEnvironmentSuppressed)
                                  =  ConfigParser.extractAsConfig(appConfigEnvEntriesAll, "hmrc_config.")

      baseConf                    <- lookupBaseConf(serviceName, optSlugInfo)

      optAppConfigCommonRaw       <- serviceType.fold(Future.successful(None: Option[String]))(st => configConnector.serviceCommonConfigYaml(environment.slugInfoFlag, st))
                                       .map(optRaw => ConfigParser.parseYamlStringAsProperties(optRaw.getOrElse("")))

      (appConfigCommonOverrideable, appConfigCommonOverrideableSuppressed)
                                  =  ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.overridable.")

      (appConfigCommonFixed, appConfigCommonFixedSuppressed)
                                  =  ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.fixed.")
    } yield
      ConfigSourceEntries("loggerConf"                 , loggerConfMap) +:
      toConfigSourceEntries(
        ConfigSourceConfig("referenceConf"             , referenceConf              , Map.empty)                             ::
        bootstrapConf.map { case (k, v) => ConfigSourceConfig(k, v, Map.empty) }.toList                                      :::
        ConfigSourceConfig("applicationConf"           , applicationConf            , Map.empty)                             ::
        ConfigSourceConfig("baseConfig"                , baseConf                   , Map.empty)                             ::
        ConfigSourceConfig("appConfigCommonOverridable", appConfigCommonOverrideable, appConfigCommonOverrideableSuppressed) ::
        ConfigSourceConfig("appConfigEnvironment"      , appConfigEnvironment       , appConfigEnvironmentSuppressed)        ::
        ConfigSourceConfig("appConfigCommonFixed"      , appConfigCommonFixed       , appConfigCommonFixedSuppressed)        ::
        Nil
      )

  def configByEnvironment(serviceName: String)(implicit hc: HeaderCarrier): Future[ConfigByEnvironment] =
    environments.toList.foldLeftM[Future, ConfigByEnvironment](Map.empty) { (map, e) =>
      configSourceEntries(e, serviceName)
        .map(cse => map + (e.name -> cse))
    }

  // TODO consideration for deprecated naming? e.g. application.secret -> play.crypto.secret -> play.http.secret.key
  def configByKey(serviceName: String)(implicit hc: HeaderCarrier): Future[ConfigByKey] =
    environments.toList
      .foldLeftM[Future, ConfigByKey](Map.empty) {
        case (map, e) =>
          configSourceEntries(e, serviceName).map { cses =>
            cses.foldLeft(map) {
              case (subMap, cse) =>
                subMap ++ cse.entries.map {
                  case (key, value) =>
                    val envMap = subMap.getOrElse(key, Map.empty)
                    val values = envMap.getOrElse(e.name, Seq.empty)
                    key -> (envMap + (e.name -> (values :+ ConfigSourceValue(cse.source, value))))
                }
            }
          }
        // sort by keys
      }
      .map { map =>
        scala.collection.immutable.ListMap(map.toSeq.sortBy(_._1): _*)
      }

  def appConfig(serviceName: String)(implicit hc: HeaderCarrier): Future[Seq[ConfigSourceEntries]] =
    for {
      optSlugInfo <- slugInfoRepository.getSlugInfo(serviceName, SlugInfoFlag.Latest)
      (applicationConf, bootstrapConf)
                  <- lookupApplicationConf(serviceName, Nil, optSlugInfo)
    } yield toConfigSourceEntries(
      ConfigSourceConfig("applicationConf", applicationConf, Map.empty) ::
      Nil
    )
}

object ConfigService {
  type EnvironmentName = String
  type KeyName         = String

  type ConfigByEnvironment = Map[EnvironmentName, Seq[ConfigSourceEntries]]
  type ConfigByKey         = Map[KeyName, Map[EnvironmentName, Seq[ConfigSourceValue]]]

  case class ConfigSourceConfig(
    name         : String,
    config       : Config,
    suppressed   : Map[String, String]
  )

  case class ConfigSourceEntries(
    source    : String,
    entries   : Map[KeyName, String]
  )

  case class ConfigSourceValue(
    source    : String,
    value     : String
  )

  case class EnvironmentMapping(
    name         : String,
    slugInfoFlag : SlugInfoFlag
  )
}
