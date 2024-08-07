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
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{CommitId, DependencyConfig, DeploymentDateRange, Environment, FileName, FilterType, ServiceName, ServiceType, SlugInfo, SlugInfoFlag, Tag, TeamName, Version}
import uk.gov.hmrc.serviceconfigs.parser.{ConfigParser, ConfigValue}
import uk.gov.hmrc.serviceconfigs.persistence.{AppliedConfigRepository, DependencyConfigRepository, DeployedConfigRepository, DeploymentEventRepository, SlugInfoRepository}

import java.util.Base64
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

@Singleton
class ConfigService @Inject()(
  configConnector           : ConfigConnector,
  slugInfoRepository        : SlugInfoRepository,
  dependencyConfigRepository: DependencyConfigRepository,
  deploymentEventRepository : DeploymentEventRepository,
  appliedConfigRepository   : AppliedConfigRepository,
  deployedConfigRepository  : DeployedConfigRepository,
  appConfigService          : AppConfigService,
  teamsAndReposConnector    : TeamsAndRepositoriesConnector
)(using ec: ExecutionContext):

  import ConfigService._

  private def lookupLoggerConfig(optSlugInfo: Option[SlugInfo]): Map[String, ConfigValue] =
    optSlugInfo match
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

  private def lookupDependencyConfigs(optSlugInfo: Option[SlugInfo]): Future[List[DependencyConfig]] =
    optSlugInfo match
      case Some(slugInfo) =>
        slugInfo.dependencies.foldLeftM(List.empty[DependencyConfig]):
          case (acc, d) =>
            dependencyConfigRepository
              .getDependencyConfig(d.group, d.artifact, d.version)
              .map(acc ++ _)
      case None => Future.successful(List.empty[DependencyConfig])

  /** Gets application config from slug info or config connector (Java apps).
    * Exposes the raw bootstrap conf if its at the start of the file, otherwise merges it in.
    */
  private def lookupApplicationConf(
    serviceName     : ServiceName,
    referenceConfigs: List[DependencyConfig],
    optSlugInfo     : Option[SlugInfo]
  )(using hc: HeaderCarrier): Future[(Config, Map[String, Config])] =
    for
      applicationConfRaw <- optSlugInfo
                              .traverse:
                                // if no slug info (e.g. java apps) get from github
                                case x if x.applicationConfig == "" => configConnector.applicationConf(serviceName, CommitId("HEAD")) // TODO provide the version to look up the correct tag. Also comment that it will not find application.conf for non-standard (incl. multi-module) builds.
                                case x                              => Future.successful(Some(x.applicationConfig))
                              .map(_.flatten.getOrElse(""))
      regex              =  """^include\s+["'](frontend.conf|backend.conf)["']""".r.unanchored
      optBootstrapFile   =  applicationConfRaw
                              .split("\n")
                              .filterNot(_.trim.startsWith("#"))
                              .mkString("\n")
                              .trim match
                                case regex(v) => Some(v)
                                case _        => None
      bootstrapConf      =  referenceConfigs
                              .flatMap(_.configs)
                              .collect:
                                case ("frontend.conf", v) if optBootstrapFile.contains("frontend.conf") => "bootstrapFrontendConf" -> ConfigParser.parseConfString(v)
                                case ("backend.conf", v)  if optBootstrapFile.contains("backend.conf")  => "bootstrapBackendConf"  -> ConfigParser.parseConfString(v)
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
    yield
      (applicationConf, bootstrapConf)

  /** Converts the unresolved configurations for each level into a
    * list of the effective configs
    */
  private def toConfigSourceEntries(cscs: Seq[ConfigSourceConfig]): Seq[ConfigSourceEntries] =
    val (cses, lastConfig) = cscs.foldLeft((Seq.empty[ConfigSourceEntries], None: Option[Config])):
      case ((acc, optPreviousConfig), entry) =>
        val (nextConfig, entries) = optPreviousConfig match
            case None                 => (entry.config, ConfigParser.flattenConfigToDotNotation(entry.config))
            case Some(previousConfig) => ConfigParser.delta(entry.config, previousConfig)
        val suppressed: Map[String, ConfigValue] =
          (ConfigParser.suppressed(entry.config, optPreviousConfig) ++ entry.suppressed)
            .map:
              case (k, _) => k -> ConfigValue.Suppressed
            .filterNot:
              case (k, _) => k.startsWith("logger.") && k != "logger.resource" // This assumes that logging was defined in system.properties or the key was quoted
        (acc :+ ConfigSourceEntries(entry.name, entry.sourceUrl, entries ++ suppressed), Some(nextConfig))
    // ApplicationLoader in bootstrap will decode any ".base64" keys and replace the keys without the .base64 extension
    lastConfig match
      case Some(config) =>
        val base64 = ConfigParser.flattenConfigToDotNotation(config).flatMap:
          case (k, v) if k.endsWith(".base64") => Map(k.replaceAll("\\.base64$", "") -> ConfigValue(Try(String(Base64.getDecoder.decode(v.asString), "UTF-8")).getOrElse("<<Invalid base64>>")))
          case _                               => Map.empty
        cses :+ ConfigSourceEntries("base64", sourceUrl = None, base64)
      case None =>
        cses

  def configSourceEntries(
    environment : ConfigEnvironment,
    serviceName : ServiceName,
    version     : Option[Version],
    latest      : Boolean // true - latest (as would be deployed), false - as currently deployed
  )(using
    hc: HeaderCarrier
  ): Future[Seq[ConfigSourceEntries]] =
    environment.slugInfoFlag match
      case SlugInfoFlag.Latest =>
        for
          optSlugInfo               <- version match {
                                         case Some(v) => slugInfoRepository.getSlugInfos(serviceName, version).map(_.headOption)
                                         case None    => slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)
                                       }
          dependencyConfigs         <- lookupDependencyConfigs(optSlugInfo)
          referenceConf             =  ConfigParser.reduceConfigs(dependencyConfigs)

          (applicationConf, bootstrapConf)
                                    <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)
        yield toConfigSourceEntries(
          ConfigSourceConfig.referenceConf(referenceConf, Map.empty) ::
          bootstrapConf.map { case (k, v) => ConfigSourceConfig(k, sourceUrl = None, v, Map.empty) }.toList :::
          ConfigSourceConfig.applicationConf(serviceName)(applicationConf, Map.empty) ::
          Nil
        )
      case SlugInfoFlag.ForEnvironment(env) =>
        for
          (optAppConfigBase, optAppConfigCommonRaw, appConfigEnvEntriesAll)
                                    <-
                                       if latest then
                                         for
                                           optAppConfigEnvRaw          <- appConfigService.appConfigEnvYaml(env, serviceName)
                                           appConfigEnvEntriesAll      =  ConfigParser
                                                                            .parseYamlStringAsProperties(optAppConfigEnvRaw.getOrElse(""))
                                           serviceType                 =  appConfigEnvEntriesAll.entrySet.asScala.find(_.getKey == "type").map(_.getValue.toString)
                                           optAppConfigBase            <- appConfigService.appConfigBaseConf(serviceName)
                                           optRaw                      <- serviceType.fold(Future.successful(Option.empty[String])): st =>
                                                                            appConfigService.appConfigCommonYaml(env, st)
                                         yield
                                           ( optAppConfigBase
                                           , ConfigParser.parseYamlStringAsProperties(optRaw.getOrElse(""))
                                           , appConfigEnvEntriesAll: java.util.Properties
                                           )
                                       else
                                         deployedConfigRepository
                                           .find(serviceName, env)
                                           .map(_.fold((Option.empty[String], java.util.Properties(), java.util.Properties()))(c =>
                                             ( c.appConfigBase
                                             , ConfigParser.parseYamlStringAsProperties(c.appConfigCommon.getOrElse(""))
                                             , ConfigParser.parseYamlStringAsProperties(c.appConfigEnv.getOrElse(""))
                                             )
                                           ))
          serviceType               =  appConfigEnvEntriesAll.entrySet.asScala.find(_.getKey == "type").map(_.getValue.toString)
          cses                      <- toConfigSourceEntries(
                                         env,
                                         serviceName,
                                         version,
                                         serviceType,
                                         appConfigEnvEntriesAll,
                                         optAppConfigBase,
                                         optAppConfigCommonRaw
                                       )
        yield cses


  def toConfigSourceEntries(
    env                   : Environment,
    serviceName           : ServiceName,
    version               : Option[Version],
    serviceType           : Option[String],
    appConfigEnvEntriesAll: java.util.Properties,
    optAppConfigBase      : Option[String],
    optAppConfigCommonRaw : java.util.Properties
  )(using HeaderCarrier): Future[Seq[ConfigSourceEntries]] =
    for
      optSlugInfo                 <- version match {
                                        case Some(v) => slugInfoRepository.getSlugInfos(serviceName, version).map(_.headOption)
                                        case None    => slugInfoRepository.getSlugInfo(serviceName, SlugInfoFlag.ForEnvironment(env))
                                      }
      loggerConfMap               =  lookupLoggerConfig(optSlugInfo)

      dependencyConfigs           <- lookupDependencyConfigs(optSlugInfo)
      referenceConf               =  ConfigParser.reduceConfigs(dependencyConfigs)

      (applicationConf, bootstrapConf)
                                  <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)

      (appConfigEnvironment, appConfigEnvironmentSuppressed) =
        ConfigParser.extractAsConfig(appConfigEnvEntriesAll, "hmrc_config.")

      appConfigBase               =  // if optAppConfigBase is defined, then this was the version used at deployment time
                                     // otherwise it's the one in the slug (or non-existant e.g. Java slugs)
                                     optAppConfigBase.orElse(optSlugInfo.map(_.slugConfig))

      baseConf =
        ConfigParser.parseConfString(appConfigBase.getOrElse(""), logMissing = false) // ignoring includes, since we know this is applicationConf

      (appConfigCommonOverrideable, appConfigCommonOverrideableSuppressed) =
        ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.overridable.")

      (appConfigCommonFixed, appConfigCommonFixedSuppressed) =
        ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.fixed.")

    yield
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

  def configByEnvironment(serviceName: ServiceName, environments: Seq[Environment], version: Option[Version], latest: Boolean)(using hc: HeaderCarrier): Future[Map[ConfigEnvironment, Seq[ConfigSourceEntries]]] =
    ConfigEnvironment
      .values
      .filter:
        case ConfigEnvironment.Local             => environments.isEmpty
        case ConfigEnvironment.ForEnvironment(e) => environments.contains(e) || environments.isEmpty
      .map: e =>
        configSourceEntries(e, serviceName, version, latest)
          .map(e -> _)
      .sequence.map(_.toMap)

  def getDeploymentEvents(serviceName: ServiceName, dateRange: DeploymentDateRange): Future[Seq[DeploymentEventRepository.DeploymentEvent]] =
    deploymentEventRepository.findAllForService(serviceName, dateRange)

  def configChangesNextDeployment(serviceName: ServiceName, environment: Environment, version: Version)(using HeaderCarrier): Future[Map[String, (Option[ConfigValue], Option[ConfigValue])]] =
    for
      current      <- configSourceEntries(
                        environment = ConfigEnvironment.ForEnvironment(environment),
                        serviceName = serviceName,
                        version     = None,
                        latest      = false
                      ).map(resultingConfig)
      whenDeployed <- configSourceEntries(
                       environment = ConfigEnvironment.ForEnvironment(environment),
                       serviceName = serviceName,
                       version     = Some(version),
                       latest      = true
                     ).map(resultingConfig)
    yield changes(current, whenDeployed)

  private def changes(fromConfig: Map[String, ConfigSourceValue], toConfig: Map[String, ConfigSourceValue]): Map[String, (Option[ConfigValue], Option[ConfigValue])] =
    (fromConfig.keys ++ toConfig.keys)
      .foldLeft(Map.empty[String, (Option[ConfigValue], Option[ConfigValue])]): (acc, key) =>
        val fromValue = fromConfig.get(key).map(_.value)
        val toValue   = toConfig.get(key).map(_.value)
        if fromValue == toValue then
          acc
        else
          acc + (key -> (fromValue, toValue))

  def configChanges(deploymentId: String, fromDeploymentId: Option[String])(using HeaderCarrier): Future[Option[ConfigChanges]] =
    for
      optTo   <- deploymentEventRepository.findDeploymentEvent(deploymentId)
      to      =  optTo.getOrElse(sys.error(s"deploymentId $deploymentId not found")) // TODO
      optFrom <- fromDeploymentId.fold(deploymentEventRepository.findPreviousDeploymentEvent(to))(deploymentEventRepository.findDeploymentEvent)
      from    =  optFrom.getOrElse(sys.error(s"deploymentId $deploymentId not found")) // TODO
      // TODO confirm fromDeploymentId corresponds to the same service/environment and an earlier timestamp

      _ = if from.serviceName != to.serviceName || from.environment != to.environment
          then sys.error(s"Can't compare deploymentIds for different service or environment")
      _ = if from.time.isAfter(to.time)
          then sys.error(s"Deployment event refered to by fromDeploymentId is later than toDeploymentId")

      (fromBaseCommitId, fromCommonCommitId, fromEnvCommitId) =
        configCommidIds(from)

      (toBaseCommitId, toCommonCommitId, toEnvCommitId) =
        configCommidIds(to)

      fromConfig <- configSourceEntriesByDeployment(from).map(resultingConfig)
      toConfig   <- configSourceEntriesByDeployment(to).map(resultingConfig)
    yield Some(ConfigChanges(
      base             = ConfigChanges.BaseConfigChange(fromBaseCommitId, toBaseCommitId),
      common           = ConfigChanges.CommonConfigChange(fromCommonCommitId, toCommonCommitId),
      env              = ConfigChanges.EnvironmentConfigChange(from.environment, fromEnvCommitId, toEnvCommitId),
      changes          = changes(fromConfig, toConfig)
    ))

  /** This a non-cached version of `configSourceEntries`
    * meaning it goes to Github
    */
  def configSourceEntriesByDeployment(
    deploymentEvent : DeploymentEventRepository.DeploymentEvent
  )(using
    hc: HeaderCarrier
  ): Future[Seq[ConfigSourceEntries]] =
    val serviceName = deploymentEvent.serviceName
    val version     = deploymentEvent.version
    val env         = deploymentEvent.environment

    // TODO return None if we don't have the commitIds, rather than partial config
    // TODO return these commitIds as github diff urls
    val (baseCommitId, commonCommitId, envCommitId) =
      configCommidIds(deploymentEvent)

    for
      optAppConfigBase            <- baseCommitId.traverse(configConnector.appConfigBaseConf(serviceName, _)).map(_.flatten) //: Future[Option[String]] =
      appConfigEnvRaw             <- envCommitId.traverse(configConnector.appConfigEnvYaml(env, serviceName, _)).map(_.flatten) //: Future[Option[String]] =
      appConfigEnvEntriesAll      =  ConfigParser.parseYamlStringAsProperties(appConfigEnvRaw.getOrElse(""))
      serviceType                 =  appConfigEnvEntriesAll.entrySet.asScala.find(_.getKey == "type").map(_.getValue.toString)
      optAppConfigCommonRaw1      <- serviceType.fold(Future.successful(Option.empty[String])): st =>
                                        commonCommitId.traverse(configConnector.appConfigCommonYaml(FileName(s"${env.asString}-$st-common.yaml"), _)).map(_.flatten)
      optAppConfigCommonRaw       = ConfigParser.parseYamlStringAsProperties(optAppConfigCommonRaw1.getOrElse(""))

      cses                      <- toConfigSourceEntries(
                                      env,
                                      serviceName,
                                      Some(version),
                                      serviceType,
                                      appConfigEnvEntriesAll,
                                      optAppConfigBase,
                                      optAppConfigCommonRaw
                                    )
    yield
      cses


  def configCommidIds(deploymentEvent: DeploymentEventRepository.DeploymentEvent): (Option[CommitId], Option[CommitId], Option[CommitId]) =
    // We could get the full commitIds from releasesApi
    // but the short hashes stored in the configId suffice
    deploymentEvent.configId match
      case Some(configId) =>
        val configs = configId.stripPrefix(deploymentEvent.serviceName.asString + "_" + deploymentEvent.version.original + "_").split("_").grouped(2).map(_.toSeq).toSeq
        ( configs.collectFirst { case Seq("app-config-base"            , v) => CommitId(v) }
        , configs.collectFirst { case Seq("app-config-common"          , v) => CommitId(v) }
        , configs.collectFirst { case Seq(s"app-config-${deploymentEvent.environment.asString}", v) => CommitId(v) }
        )
      case _ => (None, None, None)

  def resultingConfig(
    configSourceEntries: Seq[ConfigSourceEntries]
  ): Map[String, ConfigSourceValue] =
    configSourceEntries
      .flatMap(x => x.entries.view.mapValues(v => ConfigSourceValue(x.source, x.sourceUrl, v)).toSeq)
      .groupBy(_._1)
      .map:
        case (k, vs) => k -> vs.lastOption.map(_._2)
      .collect:
        case (k, Some(v)) => k -> v

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
    for
      serviceNames <- (teamName, serviceType, tags) match
                        case (None, None, Nil) => Future.successful(None)
                        case _                 => teamsAndReposConnector.getRepos(teamName = teamName, serviceType = serviceType, tags = tags)
                                                    .map(_.map(repo => ServiceName(repo.repoName.asString)))
                                                    .map(Some.apply)
      configRepos  <- appliedConfigRepository.search(
                        serviceNames    = serviceNames
                      , environments    = environments
                      , key             = key
                      , keyFilterType   = keyFilterType
                      , value           = value
                      , valueFilterType = valueFilterType
                      )
    yield configRepos

  def findConfigKeys(teamName: Option[TeamName]): Future[Seq[String]] =
    teamName.fold(appliedConfigRepository.findConfigKeys(None)): _ =>
      for
        repos        <- teamsAndReposConnector.getRepos(teamName = teamName)
        serviceNames =  repos.map(repo => ServiceName(repo.repoName.asString))
        configKeys   <- appliedConfigRepository.findConfigKeys(Some(serviceNames))
      yield configKeys

  def appConfig(slugInfo: SlugInfo)(using hc: HeaderCarrier): Future[Seq[ConfigSourceEntries]] =
    for
      dc      <- lookupDependencyConfigs(Some(slugInfo))
      (ac, _) <- lookupApplicationConf(slugInfo.name, dc, Some(slugInfo))
    yield
      toConfigSourceEntries(Seq(
        ConfigSourceConfig.applicationConf(slugInfo.name)(ac, Map.empty)
      ))

object ConfigService:
  type KeyName         = String

  case class ConfigSourceConfig(
    name         : String,
    sourceUrl    : Option[String],
    config       : Config,
    suppressed   : Map[String, ConfigValue]
  )

  object ConfigSourceConfig:
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

  case class ConfigSourceEntries(
    source    : String,
    sourceUrl : Option[String],
    entries   : Map[KeyName, ConfigValue]
  )

  case class ConfigSourceValue(
    source    : String,
    sourceUrl : Option[String],
    value     : ConfigValue
  ):
    def toRenderedConfigSourceValue =
      RenderedConfigSourceValue(
        source,
        sourceUrl,
        value.asString
      )

  case class RenderedConfigSourceValue(
    source   : String,
    sourceUrl: Option[String],
    value    : String
  )

  enum ConfigEnvironment(val name: String, val slugInfoFlag: SlugInfoFlag):
    case Local                            extends ConfigEnvironment(name = "local", slugInfoFlag = SlugInfoFlag.Latest)
    case ForEnvironment(env: Environment) extends ConfigEnvironment(name = env.asString, slugInfoFlag = SlugInfoFlag.ForEnvironment(env))

  object ConfigEnvironment:
    val values: List[ConfigEnvironment] =
      Local :: Environment.values.toList.map(ForEnvironment.apply)


  case class ConfigChanges(
    base            : ConfigChanges.BaseConfigChange,
    common          : ConfigChanges.CommonConfigChange,
    env             : ConfigChanges.EnvironmentConfigChange,
    changes         : Map[String, (Option[ConfigValue], Option[ConfigValue])]
  )

  object ConfigChanges:
    case class BaseConfigChange(from: Option[CommitId], to: Option[CommitId]):
      def githubUrl = s"https://github.com/hmrc/app-config-base/compare/${from.fold("")(_.asString)}...${to.fold("")(_.asString)}"
    case class CommonConfigChange(from: Option[CommitId], to: Option[CommitId]):
      def githubUrl = s"https://github.com/hmrc/app-config-common/compare/${from.fold("")(_.asString)}...${to.fold("")(_.asString)}"
    case class EnvironmentConfigChange(environment: Environment, from: Option[CommitId], to: Option[CommitId]):
      def githubUrl = s"https://github.com/hmrc/app-config-${environment.asString}/compare/${from.fold("")(_.asString)}...${to.fold("")(_.asString)}"
