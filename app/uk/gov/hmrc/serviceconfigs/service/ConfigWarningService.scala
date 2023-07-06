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

  def configByEnvironment(env: Environment, serviceName: ServiceName, latest: Boolean)(implicit hc: HeaderCarrier): Future[Seq[(KeyName, ConfigSourceValue, String)]] =
    for {
      configSourceEntries <- configService.configSourceEntries(ConfigService.ConfigEnvironment.ForEnvironment(env), serviceName, latest)
      resultingConfig     <- // TODO resultingConfig recalculates configSourceEntries - change to a function of configSourceEntries
                             configService.resultingConfig(ConfigService.ConfigEnvironment.ForEnvironment(env), serviceName, latest) // Future[Map[String, ConfigSourceValue]] =
      _                   =  println(s"configSourceEntries=${configSourceEntries.mkString("\n")}")
      nov                 =  configNotOverriding(configSourceEntries)
      _                   =  println(s"--------- notOverriding ---------\n${nov.mkString("\n")}")
      _                   =  configTypeChange(configSourceEntries)
      ulh                 =  useOfLocalhost(resultingConfig)
      _                   =  println(s"--------- useOfLocalhost ---------\n${ulh.mkString("\n")}")
      udb                 =  useOfDebug(resultingConfig) // TODO if env == Environment.Production
      _                   =  println(s"--------- useOfDebug ---------\n${udb.mkString("\n")}")
      tor                 =  testOnlyRoutes(resultingConfig) // TODO if env == Environment.Production
      _                   =  println(s"--------- testOnlyRoutes ---------\n${tor.mkString("\n")}")
      rmc                 =  reactiveMongoConfig(resultingConfig)
      _                   =  println(s"--------- reactiveMongoConfig ---------\n${rmc.mkString("\n")}")

    } yield
      nov.map { case (k, csv) => (k, csv, "NotOverriding") } ++
      ulh.map { case (k, csv) => (k, csv, "Localhost") } ++
      udb.map { case (k, csv) => (k, csv, "DEBUG") } ++
      tor.map { case (k, csv) => (k, csv, "TestOnlyRoutes") } ++
      rmc.map { case (k, csv) => (k, csv, "ReactiveMongoConfig") }

  private def configNotOverriding(configSourceEntries: Seq[ConfigSourceEntries]): Seq[(KeyName, ConfigSourceValue)] = {
    val (overrides, overrideable) =
      configSourceEntries.collect {
        case cse if List("baseConfig", "appConfigEnvironment").contains(cse.source) => Left(cse)
        case cse if List("referenceConf", "applicationConf").contains(cse.source) => Right(cse)
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
        && !(k.startsWith("microservice.services") && k.endsWith(".protocol")) =>
          k -> csv
     }
  }

  private def configTypeChange(configSourceEntries: Seq[ConfigSourceEntries]): Unit = {
    // TODO ConfigSourceEntries stores values as String
  }

  private def useOfLocalhost(resultingConfig: Map[KeyName, ConfigSourceValue]): Seq[(KeyName, ConfigSourceValue)] =
    resultingConfig.collect {
      case k -> csv if csv.value.contains("localhost") => k -> csv
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


//    writeConcernW=2&writeConcernJ=true&writeConcernTimeout=20000
/*

    Configuration in app-config-$env doesn't override anything. This can happen through spelling mistakes or configuration that is now obsolete. It could legitimately be optional configuration or configuration with default values in code (which we'd want to discourage)
    Configuration overrides of a different type - e.g. overridding an Array with a String
    Use of localhost (and other identifiable local development configuration)
    Use of DEBUG in production (note we're only highlighting the state of configuration, not preventing)
    testonly routes in production

We can't detect this configuration easily from config repos prs (e.g. pr commenter) since it's the combination of different config sources with different lifecycles. But we could surface in the catalogue.

    Should it be surfaced in ConfigExplorer, or a new page?
    Could it be surfaced as part of a Deploy Microservice functionality, e.g. a pre-deploy warning?

As a first step, we could run this analysis on existing deployed configuration and produce a spreadsheet to analyse?

old reactivemongo config are still being used - e.g. https://hmrcdigital.slack.com/archives/C0QBN79B9/p1685625640617509

other ideas: https://hmrcdigital.slack.com/archives/G0H0ARKNY/p1687942766828359
*/
}
