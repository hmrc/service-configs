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

package uk.gov.hmrc.serviceconfigs.parser

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.model.{Environment, YamlRoutesFile}
import uk.gov.hmrc.serviceconfigs.parser.YamlConfigParser.{YamlConfig, yamlConfigReads}
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}
import uk.gov.hmrc.serviceconfigs.util.YamlUtil.fromYaml

import javax.inject.{Inject, Singleton}

object YamlConfigParser {

  final case class LocationConfig(
    path        : String,
    shutterable : Boolean
  )

  object LocationConfig {
    val reads: Reads[LocationConfig] =
      ( (__ \ "path"       ).read[String]
      ~ (__ \ "shutterable").readWithDefault[Boolean](true)
      )(apply _)
  }

  final case class EnvironmentConfig(
    development  : Option[Seq[LocationConfig]],
    integration  : Option[Seq[LocationConfig]],
    qa           : Option[Seq[LocationConfig]],
    staging      : Option[Seq[LocationConfig]],
    externaltest : Option[Seq[LocationConfig]],
    production   : Option[Seq[LocationConfig]]
  ) {
    val asMap: Map[Environment, Seq[LocationConfig]] = Seq(
      development.map(  locations => Environment.Development  -> locations),
      integration.map(  locations => Environment.Integration  -> locations),
      qa.map(           locations => Environment.QA           -> locations),
      staging.map(      locations => Environment.Staging      -> locations),
      externaltest.map( locations => Environment.ExternalTest -> locations),
      production.map(   locations => Environment.Production   -> locations),
    ).flatten.toMap
  }

  object EnvironmentConfig {
    val reads: Reads[EnvironmentConfig] = {
      implicit val lcR: Reads[LocationConfig] = LocationConfig.reads

      ( (__ \ "development" ).readNullable[Seq[LocationConfig]]
      ~ (__ \ "integration" ).readNullable[Seq[LocationConfig]]
      ~ (__ \ "qa"          ).readNullable[Seq[LocationConfig]]
      ~ (__ \ "staging"     ).readNullable[Seq[LocationConfig]]
      ~ (__ \ "externaltest").readNullable[Seq[LocationConfig]]
      ~ (__ \ "production"  ).readNullable[Seq[LocationConfig]]
      )(apply _)
    }
  }

  final case class YamlConfig(
    service                   : String,
    environments              : EnvironmentConfig,
    zone                      : String,
    platformShutteringEnabled : Boolean
  )

  def yamlConfigReads(key: String): Reads[YamlConfig] = {
    implicit val ecR: Reads[EnvironmentConfig] = EnvironmentConfig.reads

    ( Reads.pure(key)
    ~ (__ \ "environments"       ).read[EnvironmentConfig]
    ~ (__ \ "zone"               ).readWithDefault[String]("public")
    ~ (__ \ "platform-off-switch").readWithDefault[Boolean](true)
    )(YamlConfig.apply _)
  }
}

@Singleton
class YamlConfigParser @Inject()(nginxConfig: NginxConfig) {

  def parseConfig(config: YamlRoutesFile): Set[MongoFrontendRoute] =
    if(config.content.isEmpty)
      Set.empty[MongoFrontendRoute]
    else {
      (for {
        conf        <- fromYaml[Map[String, JsValue]](config.content)
                         .map { case (k, v) => v.as[YamlConfig](yamlConfigReads(k)) }
        lines       =  config.content.linesIterator.toList
        (env, locs) <- conf.environments.asMap
        loc         <- locs
      } yield
        MongoFrontendRoute(
          service              = conf.service,
          frontendPath         = stripModifiers(loc.path),
          backendPath          = s"https://${conf.service}.${conf.zone}.mdtp",
          environment          = env,
          routesFile           = config.fileName,
          shutterKillswitch    = Option(MongoShutterSwitch(
                                   switchFile = nginxConfig.shutterConfig.shutterKillswitchPath,
                                   statusCode = Some(503)
                                 )).filter(_ => conf.platformShutteringEnabled),
          shutterServiceSwitch = Option(MongoShutterSwitch(
                                   switchFile = s"${nginxConfig.shutterConfig.shutterServiceSwitchPathPrefix}${conf.service}",
                                   statusCode = Some(503),
                                   errorPage = Some(s"/shuttered$$LANG/${conf.service}")
                                 )).filter(_ => loc.shutterable),
          ruleConfigurationUrl = s"${config.blobUrl}#${lines.indexWhere(_.contains(s"${conf.service}:")) + 1}",
          isRegex = isRegex(loc.path) // https://nginx.org/en/docs/http/ngx_http_core_module.html#location
        )
      ).toSet
    }

  private def stripModifiers(in: String): String = in
    .replaceAll("^= "   , "") // =
    .replaceAll("^~ "   , "") // ~
    .replaceAll("^~\\* ", "") // ~*
    .replaceAll("^\\^~ ", "") // ^~

  private def isRegex(loc: String): Boolean =
    loc.startsWith("= ") || loc.startsWith("~ ") || loc.startsWith("~* ") || loc.startsWith("^~ ")

}
