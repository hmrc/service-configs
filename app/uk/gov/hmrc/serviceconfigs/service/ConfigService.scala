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

import cats.data.EitherT
import cats.instances.all._
import cats.syntax.all._
import com.typesafe.config.Config
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{CommitId, DependencyConfig, DeploymentConfig, DeploymentDateRange, Environment, FileName, FilterType, ServiceName, ServiceType, SlugInfo, SlugInfoFlag, Tag, TeamName, Version}
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
                                case x if x.applicationConfig == "" => // Note, this expects application.conf to be in the standard place, which isn't true for non-standard or multi-module builds (which is often the case for java apps)
                                                                       configConnector.applicationConf(serviceName, CommitId(s"v${x.version}"))
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
  ): Future[(Seq[ConfigSourceEntries], Option[DeploymentConfig])] =
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
          cses                      =  toConfigSourceEntries(
                                         ConfigSourceConfig.referenceConf(referenceConf, Map.empty) ::
                                         bootstrapConf.map { case (k, v) => ConfigSourceConfig(k, sourceUrl = None, v, Map.empty) }.toList :::
                                         ConfigSourceConfig.applicationConf(serviceName)(applicationConf, Map.empty) ::
                                         Nil
                                       )
        yield ((cses, None))
      case SlugInfoFlag.ForEnvironment(env) =>
        for
          (optAppConfigBase, optAppConfigCommonProps, optAppConfigEnvRaw, appConfigEnvEntriesAll)
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
                                           , optAppConfigEnvRaw
                                           , appConfigEnvEntriesAll: java.util.Properties
                                           )
                                       else
                                         deployedConfigRepository
                                           .find(serviceName, env)
                                           .map(_.fold((Option.empty[String], java.util.Properties(), Option.empty[String], java.util.Properties()))(c =>
                                             ( c.appConfigBase
                                             , ConfigParser.parseYamlStringAsProperties(c.appConfigCommon.getOrElse(""))
                                             , c.appConfigEnv
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
                                         optAppConfigCommonProps
                                       )
          deploymentConfig          =  DeploymentConfigService.toDeploymentConfig(
                                         serviceName = serviceName,
                                         environment = env,
                                         applied     = true,
                                         fileContent = optAppConfigEnvRaw.getOrElse("")
                                       )
        yield ((cses, deploymentConfig))

  private def toConfigSourceEntries(
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
          .map(e -> _._1)
      .sequence.map(_.toMap)

  def getDeploymentEvents(serviceName: ServiceName, dateRange: DeploymentDateRange): Future[Seq[DeploymentEventRepository.DeploymentEvent]] =
    deploymentEventRepository.findAllForService(serviceName, dateRange)

  def configChangesNextDeployment(serviceName: ServiceName, environment: Environment, version: Version)(using HeaderCarrier): Future[ConfigChanges] =
    val configEnvironment =  ConfigEnvironment.ForEnvironment(environment)
    for
      fromVersion  <- slugInfoRepository.getSlugInfo(serviceName, configEnvironment.slugInfoFlag).map(_.map(_.version))
      current      <- configSourceEntries(
                        environment = configEnvironment,
                        serviceName = serviceName,
                        version     = None,
                        latest      = false
                      )
      whenDeployed <- configSourceEntries(
                        environment = configEnvironment,
                        serviceName = serviceName,
                        version     = Some(version),
                        latest      = true
                      )
      oDeployment  <- deploymentEventRepository.findCurrentDeploymentEvent(serviceName, environment)
      (fromBaseCommitId, fromCommonCommitId, fromEnvCommitId) =
        oDeployment.fold((Option.empty[CommitId], Option.empty[CommitId], Option.empty[CommitId]))(configCommitIds)
    yield
      ConfigChanges(
        app               = ConfigChanges.App(fromVersion, version),
        base              = ConfigChanges.BaseConfigChange(fromBaseCommitId, Some(CommitId("main"))),
        common            = ConfigChanges.CommonConfigChange(fromCommonCommitId, Some(CommitId("main"))),
        env               = ConfigChanges.EnvironmentConfigChange(environment, fromEnvCommitId, Some(CommitId("main"))),
        configChanges     = changes(resultingConfig(current._1), resultingConfig(whenDeployed._1)),
        deploymentChanges = changes(resultingConfig(current._2), resultingConfig(whenDeployed._2)),
      )

  def configChanges(deploymentId: String, fromDeploymentId: Option[String])(using HeaderCarrier): Future[Either[ConfigChangesError, ConfigChanges]] =
    (for
       to   <- EitherT.fromOptionF(
                 deploymentEventRepository.findDeploymentEvent(deploymentId),
                 ConfigChangesError.NotFound(s"deploymentId $deploymentId not found")
               )
       from <- EitherT.fromOptionF(
                 fromDeploymentId.fold(deploymentEventRepository.findPreviousDeploymentEvent(to))(deploymentEventRepository.findDeploymentEvent),
                 ConfigChangesError.NotFound(s"deploymentId $fromDeploymentId not found")
               )

       _   <- EitherT.cond(
                from.serviceName == to.serviceName && from.environment == to.environment,
                (),
                ConfigChangesError.BadRequest("Can't compare deploymentIds for different service or environment")
              )
       _   <- EitherT.cond(
                to.time.isAfter(from.time),
                (),
                ConfigChangesError.BadRequest("Deployment event referred to by fromDeploymentId is later than toDeploymentId")
              )

       (fromBaseCommitId, fromCommonCommitId, fromEnvCommitId) =
         configCommitIds(from)

       (toBaseCommitId, toCommonCommitId, toEnvCommitId) =
         configCommitIds(to)

       fromTuple <- EitherT.liftF(configSourceEntriesByDeployment(from))
       toTuple   <- EitherT.liftF(configSourceEntriesByDeployment(to))
     yield
       ConfigChanges(
         app               = ConfigChanges.App(Some(from.version), to.version),
         base              = ConfigChanges.BaseConfigChange(fromBaseCommitId, toBaseCommitId),
         common            = ConfigChanges.CommonConfigChange(fromCommonCommitId, toCommonCommitId),
         env               = ConfigChanges.EnvironmentConfigChange(from.environment, fromEnvCommitId, toEnvCommitId),
         configChanges     = changes(resultingConfig(fromTuple._1), resultingConfig(toTuple._1)),
         deploymentChanges = changes(resultingConfig(fromTuple._2), resultingConfig(toTuple._2))
       )
    ).value

  /** This a non-cached version of `configSourceEntries`
    * meaning it goes to Github
    */
  private def configSourceEntriesByDeployment(
    deploymentEvent : DeploymentEventRepository.DeploymentEvent
  )(using
    hc: HeaderCarrier
  ): Future[(Seq[ConfigSourceEntries], Option[DeploymentConfig])] =
    val serviceName = deploymentEvent.serviceName
    val version     = deploymentEvent.version
    val env         = deploymentEvent.environment

    val (baseCommitId, commonCommitId, envCommitId) =
      configCommitIds(deploymentEvent)

    for
      optAppConfigBase       <- baseCommitId.traverse(configConnector.appConfigBaseConf(serviceName, _)).map(_.flatten)
      appConfigEnvRaw        <- envCommitId.traverse(configConnector.appConfigEnvYaml(env, serviceName, _)).map(_.flatten)
      appConfigEnvEntriesAll =  ConfigParser.parseYamlStringAsProperties(appConfigEnvRaw.getOrElse(""))
      serviceType            =  appConfigEnvEntriesAll.entrySet.asScala.find(_.getKey == "type").map(_.getValue.toString)
      optAppConfigCommonRaw1 <- serviceType.fold(Future.successful(Option.empty[String])): st =>
                                  commonCommitId.traverse(configConnector.appConfigCommonYaml(FileName(s"${env.asString}-$st-common.yaml"), _)).map(_.flatten)
      optAppConfigCommonRaw  =  ConfigParser.parseYamlStringAsProperties(optAppConfigCommonRaw1.getOrElse(""))

      cses                   <- toConfigSourceEntries(
                                   env,
                                   serviceName,
                                   Some(version),
                                   serviceType,
                                   appConfigEnvEntriesAll,
                                   optAppConfigBase,
                                   optAppConfigCommonRaw
                                 )
       deploymentConfig      =  DeploymentConfigService.toDeploymentConfig(
                                  serviceName = serviceName,
                                  environment = env,
                                  applied     = true,
                                  fileContent = appConfigEnvRaw.getOrElse("")
                                )
    yield
      (cses, deploymentConfig)


  private def configCommitIds(deploymentEvent: DeploymentEventRepository.DeploymentEvent): (Option[CommitId], Option[CommitId], Option[CommitId]) =
    // We could get the full commitIds from releases-api
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

  def resultingConfig(
    deploymentConfig: Option[DeploymentConfig]
  ): Map[String, ConfigSourceValue] =
    deploymentConfig match
      case None     => Map.empty
      case Some(dc) => ( Map(
                           "instances" -> dc.instances.toString,
                           "slots"     -> dc.slots.toString
                         ) ++ dc.jvm.map(    (key, value) => (s"jvm.$key"        , value))
                           ++ dc.envVars.map((key, value) => (s"environment.$key", value))
                       ).view.mapValues:x =>
                          val url = s"https://github.com/hmrc/app-config-${dc.environment.asString}/blob/main/${dc.serviceName.asString}.yaml"
                          ConfigSourceValue(source = s"app-config-${dc.environment.asString}", sourceUrl = Some(url), ConfigValue(x))
                        .toMap

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

  def changes(fromConfig: Map[String, ConfigSourceValue], toConfig: Map[String, ConfigSourceValue]): Map[String, ConfigChange] =
    (fromConfig.keys ++ toConfig.keys)
      .foldLeft(Map.empty[String, ConfigChange]): (acc, key) =>
        val from = fromConfig.get(key)
        val to   = toConfig.get(key)
        if from.map(_.value) == to.map(_.value) then
          acc
        else
          acc + (key -> ConfigChange(from, to))

  type KeyName = String

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


  case class ConfigChange(
    from: Option[ConfigSourceValue]
  , to  : Option[ConfigSourceValue]
  )

  case class ConfigChanges(
    app              : ConfigChanges.App,
    base             : ConfigChanges.BaseConfigChange,
    common           : ConfigChanges.CommonConfigChange,
    env              : ConfigChanges.EnvironmentConfigChange,
    configChanges    : Map[String, ConfigChange],
    deploymentChanges: Map[String, ConfigChange]
  )

  object ConfigChanges:
    case class App(from: Option[Version], to: Version)
    case class BaseConfigChange(from: Option[CommitId], to: Option[CommitId]):
      def githubUrl = s"https://github.com/hmrc/app-config-base/compare/${from.fold("")(_.asString)}...${to.fold("")(_.asString)}"
    case class CommonConfigChange(from: Option[CommitId], to: Option[CommitId]):
      def githubUrl = s"https://github.com/hmrc/app-config-common/compare/${from.fold("")(_.asString)}...${to.fold("")(_.asString)}"
    case class EnvironmentConfigChange(environment: Environment, from: Option[CommitId], to: Option[CommitId]):
      def githubUrl = s"https://github.com/hmrc/app-config-${environment.asString}/compare/${from.fold("")(_.asString)}...${to.fold("")(_.asString)}"

  enum ConfigChangesError(val msg: String):
    case BadRequest(override val msg: String) extends ConfigChangesError(msg)
    case NotFound  (override val msg: String) extends ConfigChangesError(msg)
