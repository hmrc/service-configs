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

  private val localConfigSources = Seq(
      ConfigSource.ApplicationConf
    )

  private val deployedConfigSources = Seq(
      ConfigSource.ApplicationConf
    , ConfigSource.BaseConfig
    , ConfigSource.AppConfig
    , ConfigSource.AppConfigCommonFixed
    , ConfigSource.AppConfigCommonOverridable
    )

  val environments = Seq(
    Environment("local"       , localConfigSources),
    Environment("development" , deployedConfigSources),
    Environment("qa"          , deployedConfigSources),
    Environment("staging"     , deployedConfigSources),
    Environment("integration" , deployedConfigSources),
    Environment("externaltest", deployedConfigSources),
    Environment("production"  , deployedConfigSources))


  private def getServiceType(configSourceEntries: Seq[ConfigSourceEntries]): Option[String] =
    configSourceEntries.find(_.source == ConfigSource.AppConfig.name).flatMap(_.entries.get("type"))

  private def configSourceEntries(environment: Environment, serviceName: String)(implicit hc: HeaderCarrier): Future[Seq[ConfigSourceEntries]] =
    environment.configSources.toIterable
      .foldLeftM[Future, Seq[ConfigSourceEntries]](Seq.empty) { case (seq, cs) =>
        cs.entries(configConnector, configParser)(serviceName, environment.name, getServiceType(seq)).map(entries => seq :+ entries)
      }

  def configByEnvironment(serviceName: String)(implicit hc: HeaderCarrier): Future[ConfigByEnvironment] =
    environments.toIterable.foldLeftM[Future, Map[EnvironmentName, Seq[ConfigSourceEntries]]](Map.empty){ (map, e) =>
      configSourceEntries(e, serviceName)
        .map(cse => map + (e.name -> cse))
    }

  def configByKey(serviceName: String)(implicit hc: HeaderCarrier): Future[ConfigByKey] =
    environments.toIterable.foldLeftM[Future, Map[KeyName, Map[EnvironmentName, Seq[ConfigSourceValue]]]](Map.empty) { case (map, e) =>
      configSourceEntries(e, serviceName).map { cses =>
        cses.foldLeft(map) { case (subMap, cse) =>
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

  case class Environment(name: String, configSources: Seq[ConfigSource])

  sealed trait ConfigSource {
    def name: String

    def precedence: Int

    def entries(connector: ConfigConnector, parser: ConfigParser)
               (serviceName: String, env: String, serviceType: Option[String])
               (implicit hc: HeaderCarrier): Future[ConfigSourceEntries]
  }

  object ConfigSource {
    case object ApplicationConf extends ConfigSource {
      val name = "applicationConf"
      val precedence = 10

      def entries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: String, serviceType: Option[String] = None)(implicit hc: HeaderCarrier) =
        connector.serviceApplicationConfigFile(serviceName)
          .map(raw => ConfigSourceEntries(name, precedence, parser.parseConfStringAsMap(raw).getOrElse(Map.empty)))
    }

    case object BaseConfig extends ConfigSource {
      val name = "baseConfig"
      val precedence = 20

      def entries(connector: ConfigConnector, parser: ConfigParser)(serviceName: String, env: String, serviceType: Option[String] = None)(implicit hc: HeaderCarrier) =
        connector.serviceConfigConf("base", serviceName)
          .map(raw => ConfigSourceEntries(name, precedence, parser.parseConfStringAsMap(raw).getOrElse(Map.empty)))
    }

    case object AppConfig extends ConfigSource {
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

    case object AppConfigCommonFixed extends ConfigSource {
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

    case object AppConfigCommonOverridable extends ConfigSource {
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
  }


}
