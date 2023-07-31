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
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{CommitId, DependencyConfig, Environment, FilterType, ServiceName, SlugInfo, SlugInfoFlag, ServiceType, Tag, TeamName, Version}
import uk.gov.hmrc.serviceconfigs.parser.{ConfigParser, ConfigValue}
import uk.gov.hmrc.serviceconfigs.persistence.{AppliedConfigRepository, DependencyConfigRepository, DeployedConfigRepository, SlugInfoRepository}

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

@Singleton
class ConfigService @Inject()(
  configuration             : Configuration,
  configConnector           : ConfigConnector,
  slugInfoRepository        : SlugInfoRepository,
  dependencyConfigRepository: DependencyConfigRepository,
  appliedConfigRepository   : AppliedConfigRepository,
  deployedConfigRepository  : DeployedConfigRepository,
  appConfigService          : AppConfigService,
  teamsAndReposConnector    : TeamsAndRepositoriesConnector
)(implicit ec: ExecutionContext) {

  import ConfigService._

  private def lookupLoggerConfig(optSlugInfo: Option[SlugInfo]): Map[String, ConfigValue] =
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
      case _ => Map.empty[String, ConfigValue]
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
    serviceName     : ServiceName,
    referenceConfigs: List[DependencyConfig],
    optSlugInfo     : Option[SlugInfo]
  )(implicit hc: HeaderCarrier): Future[(Config, Map[String, Config])] =
    for {
      applicationConfRaw <- optSlugInfo.traverse {
                              // if no slug info (e.g. java apps) get from github
                              case x if x.applicationConfig == "" => configConnector.applicationConf(serviceName, CommitId("HEAD"))
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
                                case ("frontend.conf", v) if optBootstrapFile.contains("frontend.conf") => "bootstrapFrontendConf" -> ConfigParser.parseConfString(v)
                                case ("backend.conf", v)  if optBootstrapFile.contains("backend.conf")  => "bootstrapBackendConf"  -> ConfigParser.parseConfString(v)
                              }
                              .toMap
      includedAppConfig  =  optSlugInfo.fold(Map.empty[String, String])(_.includedAppConfig) // combine any local config split out from application.conf
      applicationConfWithoutDependencyConfig = ConfigParser.parseConfString(applicationConfRaw, includedAppConfig, logMissing = false)
      applicationConf    =  // collecting the config without dependency configs helps `delta` identify what was explicitly included in application.conf
                            // however, in the rare case that a substitution refers to something in bootstrapConf, we will need to consider it to resolve,
                            // but it will lead to thinking that all bootstrapConf entries also exist explicitly in applicationConf
                            Try(applicationConfWithoutDependencyConfig.resolve())
                              .toEither
                              .fold(
                                _ => ConfigParser.parseConfString(applicationConfRaw, includedAppConfig ++ ConfigParser.toIncludeCandidates(referenceConfigs))
                              , _ => applicationConfWithoutDependencyConfig // Note: should not be the `.resolve()` config
                              )
    } yield
      (applicationConf, bootstrapConf)

  /** Converts the unresolved configurations for each level into a
    * list of the effective configs
    */
  private def toConfigSourceEntries(cscs: Seq[ConfigSourceConfig]): Seq[ConfigSourceEntries] = {
    val (cses, lastConfig) = cscs.foldLeft((Seq.empty[ConfigSourceEntries], None: Option[Config])){ case ((acc, optPreviousConfig), entry) =>
      val (nextConfig, entries) = optPreviousConfig match {
          case None                 => (entry.config, ConfigParser.flattenConfigToDotNotation(entry.config))
          case Some(previousConfig) => ConfigParser.delta(entry.config, previousConfig)
      }
      val suppressed: Map[String, ConfigValue] =
        (ConfigParser.suppressed(entry.config, optPreviousConfig) ++ entry.suppressed)
          .map { case (k, _) => k -> ConfigValue.Suppressed }
          .filterNot { case (k, _) => k.startsWith("logger.") && k != "logger.resource" } // This assumes that logging was defined in system.properties or the key was quoted
      (acc :+ ConfigSourceEntries(entry.name, entry.sourceUrl, entries ++ suppressed), Some(nextConfig))
    }
    // ApplicationLoader in bootstrap will decode any ".base64" keys and replace the keys without the .base64 extension
    lastConfig match {
      case Some(config) =>
        val base64 = ConfigParser.flattenConfigToDotNotation(config).flatMap {
          case (k, v) if k.endsWith(".base64") => Map(k.replaceAll("\\.base64$", "") -> ConfigValue(Try(new String(Base64.getDecoder.decode(v.asString), "UTF-8")).getOrElse("<<Invalid base64>>")))
          case _                               => Map.empty
        }
        cses :+ ConfigSourceEntries("base64", sourceUrl = None, base64)
      case None =>
        cses
    }
  }

  def configSourceEntries(
    environment: ConfigEnvironment,
    serviceName: ServiceName,
    latest     : Boolean // true - latest (as would be deployed), false - as currently deployed
  )(implicit
    hc: HeaderCarrier
  ): Future[Seq[ConfigSourceEntries]] =
    environment.slugInfoFlag match {
      case SlugInfoFlag.Latest =>
        for {
          optSlugInfo               <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)

          dependencyConfigs         <- lookupDependencyConfigs(optSlugInfo)
          referenceConf             =  ConfigParser.reduceConfigs(dependencyConfigs)

          (applicationConf, bootstrapConf)
                                    <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)
        } yield toConfigSourceEntries(
          ConfigSourceConfig.referenceConf(referenceConf, Map.empty) ::
          bootstrapConf.map { case (k, v) => ConfigSourceConfig(k, sourceUrl = None, v, Map.empty) }.toList :::
          ConfigSourceConfig.applicationConf(serviceName)(applicationConf, Map.empty) ::
          Nil
        )
      case SlugInfoFlag.ForEnvironment(env) =>
        for {
          optSlugInfo                 <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)
          loggerConfMap               =  lookupLoggerConfig(optSlugInfo)

          dependencyConfigs           <- lookupDependencyConfigs(optSlugInfo)
          referenceConf               =  ConfigParser.reduceConfigs(dependencyConfigs)

          (applicationConf, bootstrapConf)
                                      <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)


          (optAppConfigBase, optAppConfigCommonRaw, appConfigEnvEntriesAll) <-
            if (latest)
              for {
                optAppConfigEnvRaw          <- appConfigService.appConfigEnvYaml(env, serviceName)

                appConfigEnvEntriesAll      =  ConfigParser
                                                .parseYamlStringAsProperties(optAppConfigEnvRaw.getOrElse(""))
                serviceType                 =  appConfigEnvEntriesAll.entrySet.asScala.find(_.getKey == "type").map(_.getValue.toString)

                optAppConfigBase            <- appConfigService.appConfigBaseConf(serviceName)

                optRaw                      <- serviceType.fold(Future.successful(None: Option[String]))(st =>
                                                 appConfigService.appConfigCommonYaml(env, st)
                                               )

              } yield
                ( optAppConfigBase
                , ConfigParser.parseYamlStringAsProperties(optRaw.getOrElse(""))
                , appConfigEnvEntriesAll: java.util.Properties
                )
            else
              deployedConfigRepository
                .find(serviceName, env)
                .map(_.fold((Option.empty[String], new java.util.Properties, new java.util.Properties))(c =>
                  ( c.appConfigBase
                  , ConfigParser.parseYamlStringAsProperties(c.appConfigCommon.getOrElse(""))
                  , ConfigParser.parseYamlStringAsProperties(c.appConfigEnv.getOrElse(""))
                  )
                ))

          serviceType                 =  appConfigEnvEntriesAll.entrySet.asScala.find(_.getKey == "type").map(_.getValue.toString)
          (appConfigEnvironment, appConfigEnvironmentSuppressed)
                                      =  ConfigParser.extractAsConfig(appConfigEnvEntriesAll, "hmrc_config.")

          appConfigBase               =  // if optAppConfigBase is defined, then this was the version used at deployment time
                                         // otherwise it's the one in the slug (or non-existant e.g. Java slugs)
                                         optAppConfigBase.orElse(optSlugInfo.map(_.slugConfig))
          baseConf                    =  ConfigParser.parseConfString(appConfigBase.getOrElse(""), logMissing = false) // ignoring includes, since we know this is applicationConf

          (appConfigCommonOverrideable, appConfigCommonOverrideableSuppressed)
                                      =  ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.overridable.")
          (appConfigCommonFixed, appConfigCommonFixedSuppressed)
                                      =  ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.fixed.")
        } yield
          ConfigSourceEntries(
            "loggerConf",
            sourceUrl = Some(s"https://github.com/hmrc/${serviceName.asString}/blob/main/conf/application-json-logger.xml"),
            loggerConfMap
          ) +:
          toConfigSourceEntries(
            ConfigSourceConfig.referenceConf(                               referenceConf              , Map.empty)                             ::
            bootstrapConf.map { case (k, v) => ConfigSourceConfig(k, sourceUrl = None, v, Map.empty) }.toList                                   :::
            ConfigSourceConfig.applicationConf(serviceName                )(applicationConf            , Map.empty)                             ::
            ConfigSourceConfig.baseConfig(serviceName                     )(baseConf                   , Map.empty)                             ::
            ConfigSourceConfig.appConfigCommonOverridable(env, serviceType)(appConfigCommonOverrideable, appConfigCommonOverrideableSuppressed) ::
            ConfigSourceConfig.appConfigEnvironment(env, serviceName      )(appConfigEnvironment       , appConfigEnvironmentSuppressed)        ::
            ConfigSourceConfig.appConfigCommonFixed(env, serviceType      )(appConfigCommonFixed       , appConfigCommonFixedSuppressed)        ::
            Nil
          )
    }

  def configByEnvironment(serviceName: ServiceName, latest: Boolean)(implicit hc: HeaderCarrier): Future[Map[ConfigEnvironment, Seq[ConfigSourceEntries]]] =
    ConfigEnvironment
      .values
      .map(e => configSourceEntries(e, serviceName, latest).map(e -> _))
      .sequence.map(_.toMap)

  def resultingConfig(
    configSourceEntries: Seq[ConfigSourceEntries]
  ): Map[String, ConfigSourceValue] =
    configSourceEntries
      .flatMap(x => x.entries.view.mapValues(v => ConfigSourceValue(x.source, x.sourceUrl, v)).toSeq)
      .groupBy(_._1)
      .map { case (k, vs) => k -> vs.lastOption.map(_._2) }
      .collect { case (k, Some(v)) => k -> v }

  def search(
    key            : Option[String],
    keyFilterType  : FilterType,
    value          : Option[String],
    valueFilterType: FilterType,
    environments   : Seq[Environment],
    teamName       : Option[TeamName],
    serviceType    : Option[ServiceType],
    tags           : Seq[Tag],
  ): Future[Seq[AppliedConfigRepository.AppliedConfig]] =
    for {
      serviceNames <- (teamName, serviceType, tags) match {
                        case (None, None, Nil) => Future.successful(None)
                        case _                 => teamsAndReposConnector.getRepos(teamName = teamName, serviceType = serviceType, tags = tags)
                                                    .map(_.map(repo => ServiceName(repo.name)))
                                                    .map(Some.apply)
                      }
      configRepos  <- appliedConfigRepository.search(
                        serviceNames    = serviceNames
                      , environments    = environments
                      , key             = key
                      , keyFilterType   = keyFilterType
                      , value           = value
                      , valueFilterType = valueFilterType
                      )
    } yield configRepos

  def findConfigKeys(teamName: Option[TeamName]): Future[Seq[String]] =
    teamName match {
      case None => appliedConfigRepository.findConfigKeys(None)
      case _    => for {
                     repos        <- teamsAndReposConnector.getRepos(teamName = teamName)
                     serviceNames =  repos.map(repo => ServiceName(repo.name))
                     configKeys   <- appliedConfigRepository.findConfigKeys(Some(serviceNames))
                   } yield configKeys
    }

  def appConfig(slugInfo: SlugInfo)(implicit hc: HeaderCarrier): Future[Seq[ConfigSourceEntries]] =
    for {
      dc      <- lookupDependencyConfigs(Some(slugInfo))
      (ac, _) <- lookupApplicationConf(slugInfo.name, dc, Some(slugInfo))
    } yield
      toConfigSourceEntries(Seq(
        ConfigSourceConfig.applicationConf(slugInfo.name)(ac, Map.empty)
      ))
}

object ConfigService {
  type KeyName         = String

  case class ConfigSourceConfig(
    name         : String,
    sourceUrl    : Option[String],
    config       : Config,
    suppressed   : Map[String, ConfigValue]
  )

  object ConfigSourceConfig {
    def referenceConf(config: Config, suppressed: Map[String, ConfigValue]): ConfigSourceConfig =
      ConfigSourceConfig(
        "referenceConf",
        sourceUrl  = None,
        config     = config,
        suppressed = suppressed
      )

    def applicationConf(serviceName: ServiceName)(config: Config, suppressed: Map[String, ConfigValue]): ConfigSourceConfig =
      ConfigSourceConfig(
        "applicationConf",
        sourceUrl  = Some(s"https://github.com/hmrc/${serviceName.asString}/blob/main/conf/application.conf"),
        config     = config,
        suppressed = suppressed
      )

    def baseConfig(serviceName: ServiceName)(config: Config, suppressed: Map[String, ConfigValue]): ConfigSourceConfig =
      ConfigSourceConfig(
        "baseConfig",
        sourceUrl  = Some(s"https://github.com/hmrc/app-config-base/blob/main/${serviceName.asString}.conf"),
        config     = config,
        suppressed = suppressed
      )

    def appConfigCommonOverridable(environment: Environment, serviceType: Option[String])(config: Config, suppressed: Map[String, ConfigValue]): ConfigSourceConfig =
      ConfigSourceConfig(
        "appConfigCommonOverridable",
        sourceUrl  = serviceType.map(st => s"https://github.com/hmrc/app-config-common/blob/main/${environment.asString}-$st-common.yaml"),
        config     = config,
        suppressed = suppressed
      )

    def appConfigEnvironment(environment: Environment, serviceName: ServiceName)(config: Config, suppressed: Map[String, ConfigValue]): ConfigSourceConfig =
      ConfigSourceConfig(
        "appConfigEnvironment",
        sourceUrl  = Some(s"https://github.com/hmrc/app-config-${environment.asString}/blob/main/${serviceName.asString}.yaml"),
        config     = config,
        suppressed = suppressed
      )

    def appConfigCommonFixed(environment: Environment, serviceType: Option[String])(config: Config, suppressed: Map[String, ConfigValue]): ConfigSourceConfig =
      ConfigSourceConfig(
        "appConfigCommonFixed",
        sourceUrl  = serviceType.map(st => s"https://github.com/hmrc/app-config-common/blob/main/${environment.asString}-$st-common.yaml"),
        config     = config,
        suppressed = suppressed
      )
  }

  case class ConfigSourceEntries(
    source    : String,
    sourceUrl : Option[String],
    entries   : Map[KeyName, ConfigValue]
  )

  case class ConfigSourceValue(
    source    : String,
    sourceUrl : Option[String],
    value     : ConfigValue
  ) {
    def toRenderedConfigSourceValue =
      RenderedConfigSourceValue(
        source,
        sourceUrl,
        value.asString
      )
  }

  case class RenderedConfigSourceValue(
    source   : String,
    sourceUrl: Option[String],
    value    : String
  )

  sealed trait ConfigEnvironment {
    def name         : String
    def slugInfoFlag : SlugInfoFlag
  }

  object ConfigEnvironment {
    case object Local                           extends ConfigEnvironment{ override def name = "local"     ; override def slugInfoFlag = SlugInfoFlag.Latest              }
    case class ForEnvironment(env: Environment) extends ConfigEnvironment{ override def name = env.asString; override def slugInfoFlag = SlugInfoFlag.ForEnvironment(env) }

    val values: List[ConfigEnvironment] =
      Local :: Environment.values.map(ForEnvironment.apply)
  }
}
