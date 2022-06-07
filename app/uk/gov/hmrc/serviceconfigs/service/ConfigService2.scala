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

package uk.gov.hmrc.serviceconfigs.service

import cats.instances.all._
import cats.syntax.all._
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.ConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, Environment, SlugInfoFlag, Version}
import uk.gov.hmrc.serviceconfigs.parser.ConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigService2 @Inject()(
  configConnector           : ConfigConnector, // TODO rename GithubConnector?
  slugInfoRepository        : SlugInfoRepository,
  dependencyConfigRepository: DependencyConfigRepository
)(implicit ec: ExecutionContext) {

  import ConfigService2._

  private val localConfigSources = Seq(
    ConfigSource.ReferenceConf,
    ConfigSource.ApplicationConf
  )

  private val deployedConfigSources = Seq(
    ConfigSource.LoggerConf,
    ConfigSource.ReferenceConf,
    ConfigSource.ApplicationConf,
    ConfigSource.BaseConfig,
    ConfigSource.AppConfig,
    ConfigSource.AppConfigCommonFixed,
    ConfigSource.AppConfigCommonOverridable
  )

  val environments: Seq[EnvironmentMapping] = Seq(
    EnvironmentMapping("local"       , SlugInfoFlag.Latest                                  , localConfigSources   ),
    EnvironmentMapping("development" , SlugInfoFlag.ForEnvironment(Environment.Development ), deployedConfigSources),
    EnvironmentMapping("qa"          , SlugInfoFlag.ForEnvironment(Environment.QA          ), deployedConfigSources),
    EnvironmentMapping("staging"     , SlugInfoFlag.ForEnvironment(Environment.Staging     ), deployedConfigSources),
    EnvironmentMapping("integration" , SlugInfoFlag.ForEnvironment(Environment.Integration ), deployedConfigSources),
    EnvironmentMapping("externaltest", SlugInfoFlag.ForEnvironment(Environment.ExternalTest), deployedConfigSources),
    EnvironmentMapping("production"  , SlugInfoFlag.ForEnvironment(Environment.Production  ), deployedConfigSources)
  )

  private def configSourceEntries(
    environment: EnvironmentMapping,
    serviceName: String
  )(implicit
    hc: HeaderCarrier
  ): Future[Seq[ConfigSourceEntries]] =
    for {
      optSlugInfo      <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)

      // loggerConfig
      loggerEntries    =  optSlugInfo match {
                            // LoggerModule was added for this version
                            case Some(slugInfo) if slugInfo.dependencies.exists(d =>
                                                  d.group == "uk.gov.hmrc"
                                                  && List("bootstrap-frontend-play-28", "bootstrap-backend-play-28").contains(d.artifact)
                                                  && Version.parse(d.version).exists(_ >= Version("5.18.0"))
                                                ) =>
                              ConfigParser
                                .parseXmlLoggerConfigStringAsMap(slugInfo.loggerConfig)
                                .getOrElse(Map.empty[String, String])
                            case _ => Map.empty[String, String]
                          }

      // referenceConfig
      configs               <- optSlugInfo match {
                                 case Some(slugInfo) =>
                                   slugInfo.dependencies.foldLeftM(List.empty[DependencyConfig]){ case (acc, d) =>
                                     dependencyConfigRepository.getDependencyConfig(d.group, d.artifact, d.version)
                                       .map(acc ++ _)
                                   }
                                 case None => Future.successful(List.empty[DependencyConfig])
                               }
      referenceConfig       =  ConfigParser.reduceConfigs(configs)
      referenceEntries      =  ConfigParser.flattenConfigToDotNotation(referenceConfig)

      // applicationConfig
      optApplicationConfRaw <- optSlugInfo.traverse {
                                 case slugInfo if slugInfo.applicationConfig == "" =>
                                   // if no slug info (e.g. java apps) get from github
                                   configConnector.serviceApplicationConfigFile(serviceName)
                                 case slugInfo =>
                                   Future.successful(Some(slugInfo.applicationConfig))
                               }.map(_.flatten)
      applicationConf       =  ConfigParser.parseConfString(optApplicationConfRaw.getOrElse(""), ConfigParser.toIncludeCandidates(configs))
      applicationEntries    =  ConfigParser.flattenConfigToDotNotation(applicationConf)

      serviceType           =  applicationEntries.get("type")

      // baseConfig
      optBaseConfRaw        <- optSlugInfo match {
                                 case Some(slugInfo) => Future.successful(Some(slugInfo.slugConfig))
                                 case None           => // if no slug info (e.g. java apps) get from github
                                                        configConnector.serviceConfigConf("base", serviceName)
                               }
      baseConf              =  ConfigParser.parseConfString(optBaseConfRaw.getOrElse(""), logMissing = false) // ignoring includes, since we know this is applicationConf
      baseEntries           =  ConfigParser.flattenConfigToDotNotation(baseConf)

      // appConfigCommonOverrideable
      optAppConfigCommonOverrideableRaw  <- serviceType.fold(Future.successful(None: Option[String]))(st => configConnector.serviceCommonConfigYaml(environment.slugInfoFlag.asString, st)) // TODO take SlugInfoFlag rather than String
      configCommonOverrideableEntries    =  ConfigParser
                                             .parseYamlStringAsMap(optAppConfigCommonOverrideableRaw.getOrElse(""))
                                             .getOrElse(Map.empty)
                                             .view
                                             .filterKeys(_.startsWith("hmrc_config.overridable"))
                                             .map { case (k, v) => k.replace("hmrc_config.overridable.", "") -> v }
                                             .toMap

      // appConfigEnv
      optAppConfigEnvRaw  <- configConnector.serviceConfigYaml(environment.slugInfoFlag.asString, serviceName) // TODO take SlugInfoFlag rather than String
      appConfigEnvEntries =  ConfigParser
                               .parseYamlStringAsMap(optAppConfigEnvRaw.getOrElse(""))
                               .getOrElse(Map.empty)
                               .map { case (k, v) => k.replace("hmrc_config.", "") -> v }


      // appConfigCommonFixed
      optAppConfigCommonFixedRaw  <- serviceType.fold(Future.successful(None: Option[String]))(st => configConnector.serviceCommonConfigYaml(environment.slugInfoFlag.asString, st))  // TODO take SlugInfoFlag rather than String
      appConfigCommonFixedEntries =  ConfigParser
                                       .parseYamlStringAsMap(optAppConfigCommonFixedRaw.getOrElse(""))
                                       .getOrElse(Map.empty)
                                       .view
                                       .filterKeys(_.startsWith("hmrc_config.fixed"))
                                       .map { case (k, v) => k.replace("hmrc_config.fixed.", "") -> v }
                                       .toMap
    } yield Seq(
      // TODO precedence is defined by order, why the numbers?
      ConfigSourceEntries("loggerConfig"               , 8, loggerEntries),
      ConfigSourceEntries("referenceConfig"            , 9, referenceEntries),
      ConfigSourceEntries("applicationConfig"          , 10, applicationEntries),
      ConfigSourceEntries("baseConfig"                 , 20, baseEntries),
      ConfigSourceEntries("appConfigCommonOverrideable", 30, configCommonOverrideableEntries),
      ConfigSourceEntries("appConfigEnv"               , 40, appConfigEnvEntries),
      ConfigSourceEntries("appConfigCommonFixed"       , 50, appConfigCommonFixedEntries)
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
                    key -> (envMap + (e.name -> (values :+ ConfigSourceValue(cse.source, cse.precedence, value))))
                }
            }
          }
        // sort by keys
      }
      .map { map =>
        scala.collection.immutable.ListMap(map.toSeq.sortBy(_._1): _*)
      }
}

object ConfigService2 {
  type EnvironmentName = String
  type KeyName         = String

  type ConfigByEnvironment = Map[EnvironmentName, Seq[ConfigSourceEntries]]
  type ConfigByKey         = Map[KeyName, Map[EnvironmentName, Seq[ConfigSourceValue]]]

  case class ConfigSourceEntries(
    source    : String,
    // TODO remove precedence and sort the results
    precedence: Int,
    entries   : Map[KeyName, String]
  )

  case class ConfigSourceValue(
    source    : String,
    // TODO remove precedence and sort the results
    precedence: Int,
    value     : String
  )

  case class EnvironmentMapping(
    name         : String,
    slugInfoFlag : SlugInfoFlag,
    configSources: Seq[ConfigSource]
  )

  sealed trait ConfigSource {
    def name: String

    def precedence: Int

    def entries(
      connector                 : ConfigConnector,
      slugInfoRepository        : SlugInfoRepository,
      dependencyConfigRepository: DependencyConfigRepository
    )(serviceName               : String,
      slugInfoFlag              : SlugInfoFlag,
      serviceType               : Option[String]
    )(implicit
      hc: HeaderCarrier,
      ec: ExecutionContext
    ): Future[ConfigSourceEntries]
  }

  object ConfigSource {
    case object LoggerConf extends ConfigSource {
      val name       = "loggerConf"
      val precedence = 8

      def entries(
        connector                 : ConfigConnector,
        slugInfoRepository        : SlugInfoRepository,
        dependencyConfigRepository: DependencyConfigRepository
      )(serviceName               : String,
        slugInfoFlag              : SlugInfoFlag,
        serviceType               : Option[String] = None
      )(implicit
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[ConfigSourceEntries] =
        for {
          optSlugInfo  <- slugInfoRepository.getSlugInfo(serviceName, slugInfoFlag)
          entries      =  optSlugInfo match {
                            // LoggerModule was added for this version
                            case Some(slugInfo) if slugInfo.dependencies.exists(d =>
                                                  d.group == "uk.gov.hmrc"
                                                  && List("bootstrap-frontend-play-28", "bootstrap-backend-play-28").contains(d.artifact)
                                                  && Version.parse(d.version).exists(_ >= Version("5.18.0"))
                                                ) =>
                              ConfigParser
                                .parseXmlLoggerConfigStringAsMap(slugInfo.loggerConfig)
                                .getOrElse(Map.empty[String, String])
                            case _ =>
                              Map.empty[String, String]
                          }
        } yield ConfigSourceEntries(name, precedence, entries)
    }

    case object ReferenceConf extends ConfigSource {
      val name       = "referenceConf"
      val precedence = 9

      def entries(
        connector                 : ConfigConnector,
        slugInfoRepository        : SlugInfoRepository,
        dependencyConfigRepository: DependencyConfigRepository
      )(serviceName               : String,
        slugInfoFlag              : SlugInfoFlag,
        serviceType               : Option[String] = None
      )(implicit
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[ConfigSourceEntries] =
        for {
          optSlugInfo     <- slugInfoRepository.getSlugInfo(serviceName, slugInfoFlag)
          configs         <- optSlugInfo match {
                               case Some(slugInfo) =>
                                 slugInfo.dependencies.foldLeftM(List.empty[DependencyConfig]){ case (acc, d) =>
                                   dependencyConfigRepository.getDependencyConfig(d.group, d.artifact, d.version)
                                     .map(acc ++ _)
                                 }
                               case None => Future.successful(List.empty[DependencyConfig])
                             }
          referenceConfig =  ConfigParser.reduceConfigs(configs)
          entries         =  ConfigParser.flattenConfigToDotNotation(referenceConfig)
        } yield ConfigSourceEntries(name, precedence, entries)
    }

    case object ApplicationConf extends ConfigSource {
      val name       = "applicationConf"
      val precedence = 10

      def entries(
        connector                 : ConfigConnector,
        slugInfoRepository        : SlugInfoRepository,
        dependencyConfigRepository: DependencyConfigRepository
      )(serviceName               : String,
        slugInfoFlag              : SlugInfoFlag,
        serviceType               : Option[String] = None
      )(implicit
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[ConfigSourceEntries] =
        for {
          optSlugInfo     <- slugInfoRepository.getSlugInfo(serviceName, slugInfoFlag)
          configs         <- optSlugInfo match {
                               case Some(slugInfo) =>
                                 slugInfo.dependencies.foldLeftM(List.empty[DependencyConfig]){ case (acc, d) =>
                                   dependencyConfigRepository.getDependencyConfig(d.group, d.artifact, d.version)
                                     .map(acc ++ _)
                                 }
                               case None => Future.successful(List.empty[DependencyConfig])
                             }
          optRaw          <- optSlugInfo.traverse {
                               case slugInfo if slugInfo.applicationConfig == "" =>
                                 // if no slug info (e.g. java apps) get from github
                                 connector.serviceApplicationConfigFile(serviceName)
                               case slugInfo =>
                                 Future.successful(Some(slugInfo.applicationConfig))
                             }.map(_.flatten)
          applicationConf =  ConfigParser.parseConfString(optRaw.getOrElse(""), ConfigParser.toIncludeCandidates(configs))
          entries         =  ConfigParser.flattenConfigToDotNotation(applicationConf)
        } yield ConfigSourceEntries(name, precedence, entries)
    }

    case object BaseConfig extends ConfigSource {
      val name       = "baseConfig"
      val precedence = 20

      def entries(
        connector                 : ConfigConnector,
        slugInfoRepository        : SlugInfoRepository,
        dependencyConfigRepository: DependencyConfigRepository
      )(serviceName               : String,
        slugInfoFlag              : SlugInfoFlag,
        serviceType               : Option[String] = None
      )(implicit
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[ConfigSourceEntries] =
        for {
          optSlugInfo <- slugInfoRepository.getSlugInfo(serviceName, slugInfoFlag)
          optRaw      <- optSlugInfo match {
                           case Some(slugInfo) => Future.successful(Some(slugInfo.slugConfig))
                           case None           => // if no slug info (e.g. java apps) get from github
                                                  connector.serviceConfigConf("base", serviceName)
                         }
          baseConf    = ConfigParser.parseConfString(optRaw.getOrElse(""), logMissing = false) // ignoring includes, since we know this is applicationConf
          entries     = ConfigParser.flattenConfigToDotNotation(baseConf)
        } yield ConfigSourceEntries(name, precedence, entries)
    }

    case object AppConfigCommonOverridable extends ConfigSource {
      val name       = "appConfigCommonOverridable"
      val precedence = 30

      def entries(
        connector                 : ConfigConnector,
        slugInfoRepository        : SlugInfoRepository,
        dependencyConfigRepository: DependencyConfigRepository
      )(serviceName               : String,
        slugInfoFlag              : SlugInfoFlag,
        serviceType               : Option[String] = None
      )(implicit
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[ConfigSourceEntries] =
        for {
          optRaw  <- serviceType.fold(Future.successful(None: Option[String]))(st => connector.serviceCommonConfigYaml(slugInfoFlag.asString, st))
          entries =  ConfigParser
                       .parseYamlStringAsMap(optRaw.getOrElse(""))
                       .getOrElse(Map.empty)
                       .view
                       .filterKeys(_.startsWith("hmrc_config.overridable"))
                       .map { case (k, v) => k.replace("hmrc_config.overridable.", "") -> v }
                       .toMap
        } yield ConfigSourceEntries(name, precedence, entries)
    }

    case object AppConfig extends ConfigSource {
      val name       = "appConfigEnvironment"
      val precedence = 40

      def entries(
        connector                 : ConfigConnector,
        slugInfoRepository        : SlugInfoRepository,
        dependencyConfigRepository: DependencyConfigRepository
      )(serviceName               : String,
        slugInfoFlag              : SlugInfoFlag,
        serviceType               : Option[String] = None
      )(implicit
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[ConfigSourceEntries] =
        for {
          optRaw  <- connector.serviceConfigYaml(slugInfoFlag.asString, serviceName)
          entries =  ConfigParser
                       .parseYamlStringAsMap(optRaw.getOrElse(""))
                       .getOrElse(Map.empty)
                       .map { case (k, v) => k.replace("hmrc_config.", "") -> v }
        } yield ConfigSourceEntries(name, precedence, entries)
    }

    case object AppConfigCommonFixed extends ConfigSource {
      val name       = "appConfigCommonFixed"
      val precedence = 50

      def entries(
        connector                 : ConfigConnector,
        slugInfoRepository        : SlugInfoRepository,
        dependencyConfigRepository: DependencyConfigRepository
      )(serviceName               : String,
        slugInfoFlag              : SlugInfoFlag,
        serviceType               : Option[String] = None
      )(implicit
        hc: HeaderCarrier,
        ec: ExecutionContext
      ): Future[ConfigSourceEntries] =
        for {
          optRaw  <- serviceType.fold(Future.successful(None: Option[String]))(st => connector.serviceCommonConfigYaml(slugInfoFlag.asString, st))
          entries =  ConfigParser
                       .parseYamlStringAsMap(optRaw.getOrElse(""))
                       .getOrElse(Map.empty)
                       .view
                       .filterKeys(_.startsWith("hmrc_config.fixed"))
                       .map { case (k, v) => k.replace("hmrc_config.fixed.", "") -> v }
                       .toMap
        } yield ConfigSourceEntries(name, precedence, entries)
    }
  }
}
