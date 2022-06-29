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
import com.typesafe.config.Config
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.ConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, Environment, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.serviceconfigs.parser.ConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

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

  private def lookupApplicationConf(
    serviceName     : String,
    referenceConfigs: List[DependencyConfig],
    optSlugInfo     : Option[SlugInfo]
  )(implicit hc: HeaderCarrier): Future[Config] =
    for {
      optApplicationConfRaw <- optSlugInfo.traverse {
                                case slugInfo if slugInfo.applicationConfig == "" =>
                                  // if no slug info (e.g. java apps) get from github
                                  configConnector.serviceApplicationConfigFile(serviceName)
                                case slugInfo =>
                                  Future.successful(Some(slugInfo.applicationConfig))
                              }.map(_.flatten)
    } yield
      ConfigParser.parseConfString(
        optApplicationConfRaw.getOrElse(""),
        ConfigParser.toIncludeCandidates(referenceConfigs)
      )

  private def lookupBaseConf(
    serviceName: String,
    optSlugInfo: Option[SlugInfo]
  )(implicit hc: HeaderCarrier): Future[Config] =
    for {
      optBaseConfRaw <- optSlugInfo match {
                          case Some(slugInfo) => Future.successful(Some(slugInfo.slugConfig))
                          case None           => // if no slug info (e.g. java apps) get from github
                                                 configConnector.serviceConfigBaseConf(serviceName)
                        }
    } yield
      ConfigParser.parseConfString(optBaseConfRaw.getOrElse(""), logMissing = false) // ignoring includes, since we know this is applicationConf

  /** Converts the unresolved configurations for each level into a
    * list of the effective configs
    */
  private def toConfigSourceEntries(cscs: Seq[ConfigSourceConfig]): Seq[ConfigSourceEntries] =
    cscs.foldLeft((Seq.empty[ConfigSourceEntries], None: Option[Config])){ case ((acc, optPreviousConfig), entry) =>
      val (nextConfig, entries) = optPreviousConfig match {
          case None                 => (entry.config, ConfigParser.flattenConfigToDotNotation(entry.config))
          case Some(previousConfig) => ConfigParser.delta(entry.config, previousConfig)
      }
      (acc :+ ConfigSourceEntries(entry.name, entries), Some(nextConfig))
    }._1

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

        aplicationConf            <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)
      } yield toConfigSourceEntries(Seq(
          ConfigSourceConfig("referenceConf"   , referenceConf ),
          ConfigSourceConfig("applicationConf" , aplicationConf)
        ))
    else
    for {
      optSlugInfo                 <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)

      loggerConfMap               =  lookupLoggerConfig(optSlugInfo)

      dependencyConfigs           <- lookupDependencyConfigs(optSlugInfo)
      referenceConf               =  ConfigParser.reduceConfigs(dependencyConfigs)

      applicationConf             <- lookupApplicationConf(serviceName, dependencyConfigs, optSlugInfo)

      optAppConfigEnvRaw          <- configConnector.serviceConfigYaml(environment.slugInfoFlag, serviceName)
      appConfigEnvEntriesAll      =  ConfigParser
                                       .parseYamlStringAsMap(optAppConfigEnvRaw.getOrElse(""))
                                       .getOrElse(Map.empty)
      serviceType                 =  appConfigEnvEntriesAll.get("type")
      appConfigEnvironment        =  ConfigParser.extractAsConfig(appConfigEnvEntriesAll, "hmrc_config.")

      baseConf                    <- lookupBaseConf(serviceName, optSlugInfo)

      optAppConfigCommonRaw       <- serviceType.fold(Future.successful(None: Option[String]))(st => configConnector.serviceCommonConfigYaml(environment.slugInfoFlag, st))
                                       .map(optRaw => ConfigParser.parseYamlStringAsMap(optRaw.getOrElse("")).getOrElse(Map.empty))

      appConfigCommonOverrideable =  ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.overridable.")

      appConfigCommonFixed        =  ConfigParser.extractAsConfig(optAppConfigCommonRaw, "hmrc_config.fixed.")
    } yield
      ConfigSourceEntries("loggerConf"                , loggerConfMap               ) +:
      toConfigSourceEntries(Seq(
        ConfigSourceConfig("referenceConf"             , referenceConf              ),
        ConfigSourceConfig("applicationConf"           , applicationConf            ),
        ConfigSourceConfig("baseConfig"                , baseConf                   ),
        ConfigSourceConfig("appConfigCommonOverridable", appConfigCommonOverrideable),
        ConfigSourceConfig("appConfigEnvironment"      , appConfigEnvironment       ),
        ConfigSourceConfig("appConfigCommonFixed"      , appConfigCommonFixed       )
      ))

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
}

object ConfigService {
  type EnvironmentName = String
  type KeyName         = String

  type ConfigByEnvironment = Map[EnvironmentName, Seq[ConfigSourceEntries]]
  type ConfigByKey         = Map[KeyName, Map[EnvironmentName, Seq[ConfigSourceValue]]]

  case class ConfigSourceConfig(
    name      : String,
    config    : Config
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
