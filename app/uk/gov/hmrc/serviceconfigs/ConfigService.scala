/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs

import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.cataloguefrontend.connector.ConfigConnector
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.Future
import uk.gov.hmrc.play.http.logging.MdcLoggingExecutionContext.fromLoggingDetails

class ConfigService @Inject()(configConnector: ConfigConnector, configParser: ConfigParser){
  import ConfigService._

  def configByEnvironment(serviceName: String)(implicit hc: HeaderCarrier): Future[ConfigByEnvironment] =
    allConfigs.foldLeft(Future.successful(newConfigMap)) { case (mapF, (env, source)) =>
      mapF flatMap { map => source.get(configConnector, configParser)(serviceName, env, map) }
    }

  def configByKey(map: ConfigByEnvironment): Map[String, List[ConfigByKeyEntry]] =
    map.foldLeft(Map[String, List[ConfigByKeyEntry]]()) {
      case (acc, (envSrc: EnvironmentConfigSource, keyValues: Map[String, ConfigByEnvEntry])) => {
        acc ++ keyValues.map {
          case (key: String, ce: ConfigByEnvEntry) =>
            val mySeq: List[ConfigByKeyEntry] = acc.getOrElse(key, List[ConfigByKeyEntry]())
            key -> (mySeq ++ List(ConfigByKeyEntry(envSrc._1.name, envSrc._2.name, ce.value)))
        }
      }
    }
}

@Singleton
object ConfigService {

  case class ConfigByEnvEntry(value: String)
  case class ConfigByKeyEntry(environment: String, configSource: String, value: String)

  case class Environment(name: String, configs: Seq[ConfigSource])

  type EnvironmentConfigSource = (Environment, ConfigSource)

  type ConfigByEnvironment = Map[EnvironmentConfigSource, Map[String, ConfigByEnvEntry]]
  type ConfigByKey = Map[String, Seq[ConfigByEnvironment]]



  //TODO how best to deal with this hierarchy of conifig sources?

  sealed trait ConfigSource {
    val name: String
    def get(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: Environment, map: ConfigByEnvironment)(implicit hc: HeaderCarrier): Future[ConfigByEnvironment]
  }

  case class ApplicationConf(name: String) extends ConfigSource {
    def get(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: Environment, map: ConfigByEnvironment)(implicit hc: HeaderCarrier) =
      connector.serviceApplicationConfigFile(serviceName)
        .map(raw => map + ((env, this) -> parser.loadConfResponseToMap(raw).toMap))
  }

  case class BaseConfig(name: String) extends ConfigSource {
    def get(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: Environment, map: ConfigByEnvironment)(implicit hc: HeaderCarrier) =
      connector.serviceConfigConf(env.name, serviceName)
        .map(raw => map + ((env, this) -> parser.loadConfResponseToMap(raw).toMap))
  }

  case class AppConfig(name: String) extends ConfigSource {
    def get(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: Environment, map: ConfigByEnvironment)(implicit hc: HeaderCarrier) =
      connector.serviceConfigYaml(env.name, serviceName)
        .map { raw =>
          map + ((env, this) -> parser.loadYamlResponseToMap(raw)
            .map { case (k, v) => k.replace("hmrc_config.", "") -> v }
            .toMap) }
  }

  case class AppConfigCommonFixed(name: String) extends ConfigSource {
    def get(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: Environment, map: ConfigByEnvironment)(implicit hc: HeaderCarrier) =
      for (entries <- getServiceType(map, env) match {
        case Some(serviceType) =>
          connector.serviceCommonConfigYaml(env.name, serviceType).map { raw =>
            parser.loadYamlResponseToMap(raw)
              .filterKeys(key => key.startsWith("hmrc_config.fixed"))
              .map { case (k, v) => k.replace("hmrc_config.fixed.", "") -> v }
              .toMap }
        case None => Future.successful(Map[String, ConfigByEnvEntry]())
      }) yield map + ((env, this) -> entries)
  }

  case class AppConfigCommonOverridable(name: String) extends ConfigSource {
    def get(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: Environment, map: ConfigByEnvironment)(implicit hc: HeaderCarrier) =
      for (entries <- getServiceType(map, env) match {
        case Some(serviceType) =>
          connector.serviceCommonConfigYaml(env.name, serviceType).map { raw =>
            parser.loadYamlResponseToMap(raw)
              .filterKeys(key => key.startsWith("hmrc_config.overridable"))
              .map { case (k, v) => k.replace("hmrc_config.overridable.", "") -> v }
              .toMap }
        case None => Future.successful(Map[String, ConfigByEnvEntry]())
      }) yield map + ((env, this) -> entries)
  }




  val applicationConf = ApplicationConf("applicationConf")
  val baseConfig = BaseConfig("baseConfig")
  val appConfig = AppConfig("appConfig")
  val appConfigCommonFixed = AppConfigCommonFixed("appConfigCommonFixed")
  val appConfigCommonOverridable = AppConfigCommonOverridable("appConfigCommonOverridable")

  val service = Environment(name = "internal", configs = Seq(applicationConf))
  val base = Environment(name = "base", configs = Seq(baseConfig))
  val development = Environment(name = "development", configs = Seq(appConfig, appConfigCommonFixed, appConfigCommonOverridable))
  val qa = Environment(name = "qa", configs = Seq(appConfig, appConfigCommonFixed, appConfigCommonOverridable))
  val staging = Environment(name = "staging", configs = Seq(appConfig, appConfigCommonFixed, appConfigCommonOverridable))
  val integration = Environment(name = "integration", configs = Seq(appConfig, appConfigCommonFixed, appConfigCommonOverridable))
  val externalTest = Environment(name = "externaltest", configs = Seq(appConfig, appConfigCommonFixed, appConfigCommonOverridable))
  val production = Environment(name = "production", configs = Seq(appConfig, appConfigCommonFixed, appConfigCommonOverridable))

  val environments = Seq(development, qa, staging, integration, externalTest, production)
  val allConfigs: Seq[EnvironmentConfigSource] =
      Seq(service, base, development, qa, staging, integration, externalTest, production)
      .flatMap(env => env.configs.map(c => env -> c))


  def newConfigMap = Map[EnvironmentConfigSource, Map[String, ConfigByEnvEntry]]()

  def getServiceType(map: ConfigByEnvironment, env: Environment): Option[String] =
    map((env, appConfig))
      .get("type")
      .map(t => t.value)

//
//  def prepareMapForPrint(byEnv: ConfigByEnvironment, byKey: ConfigByKey, envSource: EnvironmentConfigSource): Seq[(String, ConfigEntry, Seq[EnvironmentConfigSource])] = {
//    byEnv(envSource).map {
//      case (k, v) => (k, v, byKey(k).filter { case (key, value) => key != envSource && value == v }.keys.toSeq)
//    }.toSeq
//  }


}
