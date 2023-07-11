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
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigSourceEntries, ConfigSourceValue, KeyName}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigWarningService @Inject()(
  configService: ConfigService
)(implicit
  ec: ExecutionContext
){

  def warnings(env: Environment, serviceName: ServiceName, latest: Boolean)(implicit hc: HeaderCarrier): Future[Seq[ConfigWarning]] =
    for {
      configSourceEntries <- configService.configSourceEntries(ConfigService.ConfigEnvironment.ForEnvironment(env), serviceName, latest)
      resultingConfig     =  configService.resultingConfig(configSourceEntries)
      nov                 =  configNotOverriding(configSourceEntries)
      _                   =  configTypeChange(configSourceEntries)
      ulh                 =  useOfLocalhost(resultingConfig)
      udb                 =  if (env == Environment.Production)
                               useOfDebug(resultingConfig)
                             else Seq.empty
      tor                 =  if (env == Environment.Production)
                               testOnlyRoutes(resultingConfig)
                             else Seq.empty
      rmc                 =  reactiveMongoConfig(resultingConfig)
    } yield
      nov.map { case (k, csv) => ConfigWarning(k, csv, "NotOverriding") } ++
      ulh.map { case (k, csv) => ConfigWarning(k, csv, "Localhost") } ++
      udb.map { case (k, csv) => ConfigWarning(k, csv, "DEBUG") } ++
      tor.map { case (k, csv) => ConfigWarning(k, csv, "TestOnlyRoutes") } ++
      rmc.map { case (k, csv) => ConfigWarning(k, csv, "ReactiveMongoConfig") }

  private val ArrayRegex = "(.*)\\.\\d+".r
  private val Base64Regex = "(.*)\\.base64".r

  private def configNotOverriding(configSourceEntries: Seq[ConfigSourceEntries]): Seq[(KeyName, ConfigSourceValue)] = {
    def checkOverrides(overrideSource: String, overridableSources: Seq[String]): Seq[(KeyName, ConfigSourceValue)] = {
      val (overrides, overrideable) =
        configSourceEntries.collect {
          case cse if cse.source == overrideSource => Left(cse)
          case cse if overridableSources.contains(cse.source) => Right(cse)
        }.partitionMap(identity)

      val overrideableKeys = overrideable.flatMap(_.entries.keys)

      overrides.collect {
        case ConfigSourceEntries(source, sourceUrl, entries) =>
          entries.collect {
            case k -> v if !overrideableKeys.contains(k) => k -> ConfigSourceValue(source, sourceUrl, v.render)
          }
      }.flatten
       .collect {
        case k -> csv
          if !k.startsWith("logger.")
          && !(k.startsWith("microservice.services.") && k.endsWith(".protocol")) //
          && !(k.startsWith("play.filters.csp.directives."))
          && !(k.startsWith("java."))  // system props
          && !(k.startsWith("javax.")) // system props
          && !(k == "user.timezone")   // system props

          //&& !(k.contains("\"")) // dynamic keys - e.g. play.assets.cache."resource" (or should these always be defined in application.conf ?)
          && // ignore, if there's a related `.enabled` key
             !{ val i = k.lastIndexWhere(_ == '.')
                if (i >= 0) {
                  val enabledKey = k.substring(0, i) + ".enabled"
                  overrideable.exists(_.entries.exists(_._1 == enabledKey))
                } else false
              }

          && !{ k match {
            case ArrayRegex(key) => overrideable.exists(_.entries.exists(_._1 == key)) ||
                                    key.endsWith(".previousKeys") // crypto defaults to []
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

  private def configTypeChange(configSourceEntries: Seq[ConfigSourceEntries]): Unit = {
    // TODO ConfigSourceEntries stores values as String
  }

  private def useOfLocalhost(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if List("localhost", "127.0.0.1").exists(csv.value.contains) => k -> csv
    }.toSeq

  private def useOfDebug(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if k.startsWith("logger.") && csv.value == "DEBUG" => k -> csv
    }.toSeq

  private def testOnlyRoutes(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if List("application.router", "play.http.router").contains(k) && csv.value.contains("testOnly") => k -> csv
    }.toSeq

  private def reactiveMongoConfig(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if k == "mongodb.uri" && List("writeconcernw", "writeconcernj", "writeConcernTimeout", "rm.failover", "rm.monitorrefreshms").exists(csv.value.toLowerCase.contains) => k -> csv
    }.toSeq
}

case class ConfigWarning(
  key    : KeyName,
  value  : ConfigSourceValue,
  warning: String
)
