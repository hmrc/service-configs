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

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.parser.ConfigValueType
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigSourceEntries, ConfigSourceValue, KeyName, RenderedConfigSourceValue}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigWarningService @Inject()(
  configService: ConfigService
)(implicit
  ec: ExecutionContext
){

  def warnings(
    environment        : Environment,
    serviceName        : ServiceName,
    latest             : Boolean,
    configSourceEntries: Seq[ConfigSourceEntries],
    resultingConfig    : Map[String, ConfigSourceValue],
  ): Future[Seq[ConfigWarning]] = {
    def toConfigWarning(k: String, csv: ConfigSourceValue, warning: String) =
      ConfigWarning(
        environment = environment
      , serviceName = serviceName
      , key         = k
      , value       = csv.toRenderedConfigSourceValue
      , warning     = warning
      )

    // println(environment.asString)
    // val t0 = System.nanoTime()
    for {
      // configSourceEntries <- configService.configSourceEntries(ConfigService.ConfigEnvironment.ForEnvironment(environment), serviceName, latest)
      // t1 = System.nanoTime()
      // _ = println(s"configSourceEntries took ${(t1 - t0) / 1000000} ms")
      // resultingConfig     <-  Future.successful(configService.resultingConfig(configSourceEntries))
      // t2 = System.nanoTime()
      // _ = println(s"resultingConfig took ${(t2 - t1) / 1000000} ms")

      _ <- Future.unit // TODO no need for: for comp
      nov                 =  configNotOverriding(configSourceEntries)
      // t3 = System.nanoTime()
      // _ = println(s"nov took ${(t3 - t2) / 1000000} ms")
      ctc                 =  configTypeChange(configSourceEntries)
      // t4 = System.nanoTime()
      // _ = println(s"ctc took ${(t4 - t3) / 1000000} ms")
      ulh                 =  useOfLocalhost(resultingConfig)
      // t5 = System.nanoTime()
      // _ = println(s"ulh took ${(t5 - t4) / 1000000} ms")

      udb                 =  if (environment == Environment.Production)
                               useOfDebug(resultingConfig)
                             else Seq.empty
      // t6 = System.nanoTime()
      // _ = println(s"udb took ${(t6 - t5) / 1000000} ms")
      tor                 =  if (environment == Environment.Production)
                               testOnlyRoutes(resultingConfig)
                             else Seq.empty
      // t7 = System.nanoTime()
      // _ = println(s"tor took ${(t7 - t6) / 1000000} ms")
      rmc                 =  reactiveMongoConfig(resultingConfig)
      // t8 = System.nanoTime()
      // _ = println(s"rmc took ${(t8 - t7) / 1000000} ms")
      uec                 =  unencryptedConfig(resultingConfig)
      // t9 = System.nanoTime()
      // _ = println(s"uec took ${(t9 - t8) / 1000000} ms")
    } yield
      ( nov.map { case (k, csv) => toConfigWarning(k, csv, "NotOverriding"      ) } ++
        ctc.map { case (k, csv) => toConfigWarning(k, csv, "TypeChange"         ) } ++
        ulh.map { case (k, csv) => toConfigWarning(k, csv, "Localhost"          ) } ++
        udb.map { case (k, csv) => toConfigWarning(k, csv, "DEBUG"              ) } ++
        tor.map { case (k, csv) => toConfigWarning(k, csv, "TestOnlyRoutes"     ) } ++
        rmc.map { case (k, csv) => toConfigWarning(k, csv, "ReactiveMongoConfig") } ++
        uec.map { case (k, csv) => toConfigWarning(k, csv, "Unencrypted"        ) }
      ).sortBy(w => (w.warning, w.key))
  }

  private val ArrayRegex = "(.*)\\.\\d+(?:\\..+)?".r
  private val Base64Regex = "(.*)\\.base64".r

  private def configNotOverriding(configSourceEntries: Seq[ConfigSourceEntries]): Seq[(KeyName, ConfigSourceValue)] = {
    def checkOverrides(overrideSource: String, overridableSources: Seq[String]): Seq[(KeyName, ConfigSourceValue)] = {
      val (overrides, overrideable) =
        configSourceEntries.collect {
          case cse if cse.source == overrideSource            => Left(cse)
          case cse if overridableSources.contains(cse.source) => Right(cse)
        }.partitionMap(identity)

      val overrideableKeys = overrideable.flatMap(_.entries.keys)

      overrides.collect {
        case ConfigSourceEntries(source, sourceUrl, entries) =>
          entries.collect {
            case k -> v if !overrideableKeys.contains(k) => k -> ConfigSourceValue(source, sourceUrl, v)
          }
      }.flatten
       .collect {
        case k -> csv
          if !k.startsWith("logger.")
          && !(k.startsWith("microservice.services.") && k.endsWith(".protocol")) // default in code (bootstrap-play)
          && !(k == "auditing.consumer.baseUri.protocol") // default in code (play-auditing)
          && !(k.startsWith("play.filters.csp.directives."))
          && !(k.startsWith("java."))  // system props
          && !(k.startsWith("javax.")) // system props
          && !(k == "user.timezone")   // system props
          && // ignore, if there's a related `.enabled` key // TODO should all the related keys just be defined with `null`
             !{ val i = k.lastIndexWhere(_ == '.')
                if (i >= 0) {
                  val enabledKey = k.substring(0, i) + ".enabled"
                  overrideable.exists(_.entries.exists(_._1 == enabledKey))
                } else false
              }
          && !{ k match {
            case ArrayRegex(key) => overrideable.exists(_.entries.exists(_._1 == key)) ||
                                    key.endsWith(".previousKeys") // crypto defaults to [] // TODO require definition in application.conf
            case _               => false
          } }
          && !{ k match {
            case Base64Regex(key) => overrideable.exists(_.entries.exists(_._1 == key))
            case _                => false
          } }
          => k -> csv
       }
    }

    checkOverrides(overrideSource = "baseConfig", overridableSources = List("referenceConf", "applicationConf")) ++
      checkOverrides(overrideSource = "appConfigEnvironment", overridableSources = List("referenceConf", "applicationConf", "baseConfig", "appConfigCommonOverridable"))
  }

  private def configTypeChange(configSourceEntries: Seq[ConfigSourceEntries]): Seq[(KeyName, ConfigSourceValue)] = {
    def checkTypeOverrides(overrideSource: String, overridableSources: Seq[String]): Seq[(KeyName, ConfigSourceValue)] = {
      val (overrides, overrideable) =
        configSourceEntries.collect {
          case cse if cse.source == overrideSource => Left(cse)
          case cse if overridableSources.contains(cse.source) => Right(cse)
        }.partitionMap(identity)

      val overrideable2 = overrideable.flatMap(_.entries)

      overrides.collect {
        case ConfigSourceEntries(source, sourceUrl, entries) =>
          entries.collect {
            case k -> v if overrideable2.exists { case (k2, v2) =>
              if (k == k2 && v.valueType != v2.valueType) {
                if (
                 v.valueType == ConfigValueType.Null       || v2.valueType == ConfigValueType.Null     ||
                 v.valueType == ConfigValueType.Unmerged   || v2.valueType == ConfigValueType.Unmerged ||
                 v.valueType == ConfigValueType.Suppressed || v2.valueType == ConfigValueType.Suppressed
                )
                  false
                else
                  true
              } else
                false
             } =>
              k -> ConfigSourceValue(source, sourceUrl, v)
          }
        }
       .flatten
       .collect {
        case k -> csv
          => k -> csv
       }
      }

    checkTypeOverrides(overrideSource = "baseConfig", overridableSources = List("referenceConf", "bootstrapFrontendConf", "bootstrapBackendConf", "applicationConf")) ++
      checkTypeOverrides(overrideSource = "appConfigEnvironment", overridableSources = List("referenceConf", "bootstrapFrontendConf", "bootstrapBackendConf", "applicationConf", "baseConfig", "appConfigCommonOverridable"))
  }

  private def useOfLocalhost(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if List("localhost", "127.0.0.1").exists(csv.value.asString.contains)
                    && !List("play.http.forwarded.trustedProxies", // https://www.playframework.com/documentation/2.8.x/HTTPServer#Configuring-trusted-proxies
                             "play.filters.hosts.allowed" // provided for https://www.playframework.com/documentation/2.8.x/AllowedHostsFilter which isn't used
                        ).contains(k)
                    => k -> csv
    }.toSeq

  private def useOfDebug(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if k.startsWith("logger.") && csv.value.asString == "DEBUG" => k -> csv
    }.toSeq

  private def testOnlyRoutes(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if List("application.router", "play.http.router").contains(k) && csv.value.asString.contains("testOnly") => k -> csv
    }.toSeq

  private def reactiveMongoConfig(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if k == "mongodb.uri"
                    && List("writeconcernw", "writeconcernj", "writeConcernTimeout", "rm.failover", "rm.monitorrefreshms", "sslEnabled").exists(csv.value.asString.toLowerCase.contains)
                    => k -> csv
    }.toSeq

  def hasFunkyChars(c: Char) =
    !(Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.' || c == '*' || c == '/' || c == ':' || c == ',' || c == ' ')

  private def unencryptedConfig(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if (List("key", "secret", "pass", "token").exists(k.toLowerCase.contains))
                    && csv.value.valueType == ConfigValueType.SimpleValue
                    && csv.value.asString.exists(hasFunkyChars)
                    && !csv.value.asString.contains("ENC[")
                    => k -> csv
    }.toSeq
}

case class ConfigWarning(
  environment: Environment,
  serviceName: ServiceName,
  key        : KeyName,
  value      : RenderedConfigSourceValue,
  warning    : String
)
