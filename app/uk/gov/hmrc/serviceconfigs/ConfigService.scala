/*
 * Copyright 2019 HM Revenue & Customs
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

import alleycats.std.iterable._
    import cats.instances.all._
    import cats.syntax.all._
import javax.inject.{Inject, Singleton}
import uk.gov.hmrc.cataloguefrontend.connector.ConfigConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.parser.ConfigParser
import scala.concurrent.{ExecutionContext, Future}

import ExecutionContext.Implicits.global

@Singleton
class ConfigService @Inject()(configConnector: ConfigConnector, configParser: ConfigParser) {

  import ConfigService._

  val environments = Seq(
    LocalEnvironment("local"),
    DeployedEnvironment("development"),
    DeployedEnvironment("qa"),
    DeployedEnvironment("staging"),
    DeployedEnvironment("integration"),
    DeployedEnvironment("externaltest"),
    DeployedEnvironment("production"))

  def configByEnvironment(serviceName: String)(implicit hc: HeaderCarrier): Future[ConfigByEnvironment] =
    environments.toIterable.foldLeftM[Future, Map[EnvironmentName, Seq[ConfigSourceEntries]]](Map.empty){ (map, e) =>
      e.configSourceEntries(configConnector, configParser)(serviceName).map(cse => map + (e.name -> cse))
    }

  def configByKey(serviceName: String)(implicit hc: HeaderCarrier): Future[ConfigByKey] =
    environments.toIterable.foldLeftM[Future, Map[KeyName, Map[EnvironmentName, Seq[ConfigSourceValue]]]](Map.empty) { case (map, e) =>
      e.configSourceEntries(configConnector, configParser)(serviceName).map { configSourceEntries =>
        configSourceEntries.foldLeft(map) { case (subMap, cse) =>
          subMap ++ cse.entries.map { case (key, value) =>
            val envMap = subMap.getOrElse(key, Map[EnvironmentName, Seq[ConfigSourceValue]]())
            val values = envMap.getOrElse(e.name, Seq())
            key -> (envMap + (e.name -> (values :+ ConfigSourceValue(cse.source, cse.precedence, value))))
          }
        }
      }
    }
}

object ConfigService {
  type EnvironmentName = String
  type KeyName = String

  type ConfigByEnvironment = Map[EnvironmentName, Seq[ConfigSourceEntries]]
  type ConfigByKey = Map[KeyName, Map[EnvironmentName, Seq[ConfigSourceValue]]]

  case class ConfigSourceEntries(source: String, precedence: Int, entries: Map[KeyName, String] = Map())

  case class ConfigSourceValue(source: String, precedence: Int, value: String)

  case class LocalEnvironment(name: String) extends Environment {
    def configSources = Seq(ApplicationConf())
  }

  case class DeployedEnvironment(name: String) extends Environment {
    def configSources = Seq(ApplicationConf(), BaseConfig(), AppConfig(), AppConfigCommonFixed(), AppConfigCommonOverridable())
  }

  sealed trait Environment {
    def name: String

    def configSources: Seq[ConfigSource]

    def configSourceEntries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String)(implicit hc: HeaderCarrier) =
      configSources.toIterable.foldLeftM[Future, Seq[ConfigSourceEntries]](Seq.empty) { case (seq, cs) =>
        cs.entries(connector, parser)(serviceName, name, getServiceType(seq)).map(entries => seq :+ entries)
      }
  }

  case class ApplicationConf() extends ConfigSource {
    val name = "applicationConf"
    val precedence = 10

    def entries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: String, serviceType: Option[String] = None)(implicit hc: HeaderCarrier) =
      connector.serviceApplicationConfigFile(serviceName)
        .map(raw => ConfigSourceEntries(name, precedence, parser.parseConfStringAsMap(raw).getOrElse(Map.empty)))
  }

  case class BaseConfig() extends ConfigSource {
    val name = "baseConfig"
    val precedence = 20

    def entries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: String, serviceType: Option[String] = None)(implicit hc: HeaderCarrier) =
      connector.serviceConfigConf("base", serviceName)
        .map(raw => ConfigSourceEntries(name, precedence, parser.parseConfStringAsMap(raw).getOrElse(Map.empty)))
  }

  case class AppConfig() extends ConfigSource {
    val name = "appConfigEnvironment"
    val precedence = 40

    def entries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: String, serviceType: Option[String] = None)(implicit hc: HeaderCarrier) =
      connector.serviceConfigYaml(env, serviceName)
        .map { raw =>
          ConfigSourceEntries(
            name,
            precedence,
            parser.parseYamlStringAsMap(raw).getOrElse(Map.empty)
              .map { case (k, v) => k.replace("hmrc_config.", "") -> v }
              .toMap)
        }
  }

  case class AppConfigCommonFixed() extends ConfigSource {
    val name = "appConfigCommonFixed"
    val precedence = 50

    def entries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: String, serviceType: Option[String] = None)(implicit hc: HeaderCarrier) =
      serviceType match {
        case Some(st) =>
          connector.serviceCommonConfigYaml(env, st).map { raw =>
            ConfigSourceEntries(
              name,
              precedence,
              parser.parseYamlStringAsMap(raw).getOrElse(Map.empty)
                .filterKeys(_.startsWith("hmrc_config.fixed"))
                .map { case (k, v) => k.replace("hmrc_config.fixed.", "") -> v }
                .toMap)
          }
        case None =>
          Future.successful(ConfigSourceEntries(name, precedence))
      }
  }

  case class AppConfigCommonOverridable() extends ConfigSource {
    val name = "appConfigCommonOverridable"
    val precedence = 30

    def entries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: String, serviceType: Option[String] = None)(implicit hc: HeaderCarrier) =
      serviceType match {
        case Some(st) =>
          connector.serviceCommonConfigYaml(env, st).map { raw =>
            ConfigSourceEntries(
              name,
              precedence,
              parser.parseYamlStringAsMap(raw).getOrElse(Map.empty)
                .filterKeys(_.startsWith("hmrc_config.overridable"))
                .map { case (k, v) => k.replace("hmrc_config.overridable.", "") -> v }
                .toMap)
          }
        case None => Future.successful(ConfigSourceEntries(name, precedence))
      }
  }

  sealed trait ConfigSource {
    def name: String

    def precedence: Int

    def entries(connector: ConfigConnector, parser: ConfigParser)
               (serviceName: String, env: String, serviceType: Option[String])
               (implicit hc: HeaderCarrier): Future[ConfigSourceEntries]
  }

  def getServiceType(configSourceEntries: Seq[ConfigSourceEntries]): Option[String] =
    configSourceEntries.find(_.source == AppConfig().name).flatMap(_.entries.get("type"))
}
