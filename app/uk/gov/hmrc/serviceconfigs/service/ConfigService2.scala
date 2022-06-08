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
import com.typesafe.config.ConfigFactory
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.ConfigConnector
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, Environment, SlugInfoFlag, Version}
import uk.gov.hmrc.serviceconfigs.parser.ConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import com.typesafe.config.Config
import com.typesafe.config.ConfigResolveOptions

@Singleton
class ConfigService2 @Inject()(
  configConnector           : ConfigConnector, // TODO rename GithubConnector?
  slugInfoRepository        : SlugInfoRepository,
  dependencyConfigRepository: DependencyConfigRepository
)(implicit ec: ExecutionContext) {

  import ConfigService2._

  val environments: Seq[EnvironmentMapping] = Seq(
    EnvironmentMapping("local"       , SlugInfoFlag.Latest                                  ),
    EnvironmentMapping("development" , SlugInfoFlag.ForEnvironment(Environment.Development )),
    EnvironmentMapping("qa"          , SlugInfoFlag.ForEnvironment(Environment.QA          )),
    EnvironmentMapping("staging"     , SlugInfoFlag.ForEnvironment(Environment.Staging     )),
    EnvironmentMapping("integration" , SlugInfoFlag.ForEnvironment(Environment.Integration )),
    EnvironmentMapping("externaltest", SlugInfoFlag.ForEnvironment(Environment.ExternalTest)),
    EnvironmentMapping("production"  , SlugInfoFlag.ForEnvironment(Environment.Production  ))
  )

  def delta(conf1: Config, conf2: Config): (Config, Map[String, String]) = {
    val conf2Map = ConfigParser.flattenConfigToDotNotation(conf2)

    val conf = conf1.withFallback(conf2)
    val conf1Resolved = // we have to resolve in order to call "hasPath"
      conf1.resolve(ConfigResolveOptions.defaults.setAllowUnresolved(true).setUseSystemEnvironment(false))
    val confAsMap = ConfigParser.flattenConfigToDotNotation(conf)
    val confAsMap2 = confAsMap.foldLeft(Map.empty[String, String]){ case (acc, (k, v)) =>
        // some entries cannot be resolved. e.g. `play.server.pidfile.path -> ${play.server.dir}"/RUNNING_PID"`
        // keep it for now
        if (scala.util.Try(conf1Resolved.hasPath(k)).getOrElse{ println(s"!!Failed to evaluate $k -> $v"); true } ||
          conf2Map.get(k) != Some(v)
        )
          acc ++ Seq(k -> v) // and not explicitly included in conf2
        else
          acc
      }
    (conf, confAsMap2)
  }

  private def configSourceEntries(
    environment: EnvironmentMapping,
    serviceName: String
  )(implicit
    hc: HeaderCarrier
  ): Future[Seq[ConfigSourceEntries]] =
    if (environment.name == "local")
      for {
        optSlugInfo                       <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)

        // referenceConfig
        configs                           <- optSlugInfo match {
                                              case Some(slugInfo) =>
                                                slugInfo.dependencies.foldLeftM(List.empty[DependencyConfig]){ case (acc, d) =>
                                                  dependencyConfigRepository.getDependencyConfig(d.group, d.artifact, d.version)
                                                    .map(acc ++ _)
                                                }
                                              case None => Future.successful(List.empty[DependencyConfig])
                                            }
        referenceConfig                   =  ConfigParser.reduceConfigs(configs)
        referenceEntries                  =  ConfigParser.flattenConfigToDotNotation(referenceConfig)

        // applicationConfig
        optApplicationConfRaw             <- optSlugInfo.traverse {
                                              case slugInfo if slugInfo.applicationConfig == "" =>
                                                // if no slug info (e.g. java apps) get from github
                                                configConnector.serviceApplicationConfigFile(serviceName)
                                              case slugInfo =>
                                                Future.successful(Some(slugInfo.applicationConfig))
                                            }.map(_.flatten)
        (applicationConf, applicationEntries)                   =  delta(
                                               ConfigParser.parseConfString(optApplicationConfRaw.getOrElse(""), ConfigParser.toIncludeCandidates(configs)),
                                               referenceConfig
                                             )
      } yield Seq(
          ConfigSourceEntries("referenceConfig"            , 9 , referenceEntries),
          ConfigSourceEntries("applicationConfig"          , 10, applicationEntries),
        )
    else
    for {
      optSlugInfo                       <- slugInfoRepository.getSlugInfo(serviceName, environment.slugInfoFlag)



      // loggerConfig
      loggerEntries1                    =  optSlugInfo match {
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
      loggerConfig                      =  ConfigFactory.parseMap(loggerEntries1.asJava)
      loggerEntries                     =  ConfigParser.flattenConfigToDotNotation(loggerConfig)

      // referenceConfig
      configs                           <- optSlugInfo match {
                                             case Some(slugInfo) =>
                                               slugInfo.dependencies.foldLeftM(List.empty[DependencyConfig]){ case (acc, d) =>
                                                 dependencyConfigRepository.getDependencyConfig(d.group, d.artifact, d.version)
                                                   .map(acc ++ _)
                                               }
                                             case None => Future.successful(List.empty[DependencyConfig])
                                           }
      (referenceConfig, referenceEntries)                   =  delta(
                                             ConfigParser.reduceConfigs(configs),
                                             loggerConfig
                                           )

      // applicationConfig
      optApplicationConfRaw             <- optSlugInfo.traverse {
                                             case slugInfo if slugInfo.applicationConfig == "" =>
                                               // if no slug info (e.g. java apps) get from github
                                               configConnector.serviceApplicationConfigFile(serviceName)
                                             case slugInfo =>
                                               Future.successful(Some(slugInfo.applicationConfig))
                                           }.map(_.flatten)
      (applicationConf, applicationEntries)                   =  delta(
                                             ConfigParser.parseConfString(optApplicationConfRaw.getOrElse(""), ConfigParser.toIncludeCandidates(configs)),
                                             referenceConfig
                                           )

      optAppConfigEnvRaw                <- configConnector.serviceConfigYaml(environment.slugInfoFlag.asString, serviceName) // TODO take SlugInfoFlag rather than String
      appConfigEnvEntries1              =  ConfigParser
                                            .parseYamlStringAsMap(optAppConfigEnvRaw.getOrElse(""))
                                            .getOrElse(Map.empty)
                                            .map { case (k, v) => k.replace("hmrc_config.", "") -> v }
      serviceType                       =  appConfigEnvEntries1.get("type")

      // baseConfig
      optBaseConfRaw                    <- optSlugInfo match {
                                             case Some(slugInfo) => Future.successful(Some(slugInfo.slugConfig))
                                             case None           => // if no slug info (e.g. java apps) get from github
                                                                    configConnector.serviceConfigConf("base", serviceName)
                                           }
      (baseConf, baseEntries)                          =  delta(
                                             ConfigParser.parseConfString(optBaseConfRaw.getOrElse(""), logMissing = false), // ignoring includes, since we know this is applicationConf
                                             applicationConf
                                            )

      // appConfigCommonOverrideable
      optAppConfigCommonOverrideableRaw <- serviceType.fold(Future.successful(None: Option[String]))(st => configConnector.serviceCommonConfigYaml(environment.slugInfoFlag.asString, st)) // TODO take SlugInfoFlag rather than String
      configCommonOverrideableEntries1  =  ConfigParser
                                             .parseYamlStringAsMap(optAppConfigCommonOverrideableRaw.getOrElse(""))
                                             .getOrElse(Map.empty)
                                             .view
                                             .filterKeys(_.startsWith("hmrc_config.overridable"))
                                             .map { case (k, v) => k.replace("hmrc_config.overridable.", "") -> v }
                                             .toMap
      (configCommonOverrideableConf, configCommonOverrideableEntries)      =  delta(
                                             ConfigFactory.parseMap(configCommonOverrideableEntries1.asJava),
                                             baseConf
                                           )

      // appConfigEnv
      (appConfigEnvConf,appConfigEnvEntries)                  =  delta(
                                             ConfigFactory.parseMap(appConfigEnvEntries1.asJava),
                                             configCommonOverrideableConf
                                           )


      // appConfigCommonFixed
      optAppConfigCommonFixedRaw        <- serviceType.fold(Future.successful(None: Option[String]))(st => configConnector.serviceCommonConfigYaml(environment.slugInfoFlag.asString, st))  // TODO take SlugInfoFlag rather than String
      appConfigCommonFixedEntries1      =  ConfigParser
                                            .parseYamlStringAsMap(optAppConfigCommonFixedRaw.getOrElse(""))
                                            .getOrElse(Map.empty)
                                            .view
                                            .filterKeys(_.startsWith("hmrc_config.fixed"))
                                            .map { case (k, v) => k.replace("hmrc_config.fixed.", "") -> v }
                                            .toMap
      (appConfigCommonFixedConf, appConfigCommonFixedEntries)          =  delta(
                                             ConfigFactory.parseMap(appConfigCommonFixedEntries1.asJava),
                                             appConfigEnvConf
                                           )
    } yield Seq(
      // TODO precedence is defined by order, why the numbers?
      ConfigSourceEntries("loggerConf"                , 8 , loggerEntries),
      ConfigSourceEntries("referenceConf"             , 9 , referenceEntries),
      ConfigSourceEntries("applicationConf"           , 10, applicationEntries),
      ConfigSourceEntries("baseConfig"                , 20, baseEntries),
      ConfigSourceEntries("appConfigCommonOverridable", 30, configCommonOverrideableEntries),
      ConfigSourceEntries("appConfigEnvironment"      , 40, appConfigEnvEntries),
      ConfigSourceEntries("appConfigCommonFixed"      , 50, appConfigCommonFixedEntries)
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
    slugInfoFlag : SlugInfoFlag
  )
}
