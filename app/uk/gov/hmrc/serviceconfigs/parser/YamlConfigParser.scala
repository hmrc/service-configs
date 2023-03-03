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

  private final case class LocationConfig(
    path        : String,
    shutterable : Boolean
  )

  private object LocationConfig {
    val reads: Reads[LocationConfig] =
      ( (__ \ "path"       ).read[String]
      ~ (__ \ "shutterable").readWithDefault[Boolean](true)
      )(apply _)
  }

  private final case class YamlConfig(
    service                   : String,
    development               : Seq[LocationConfig],
    integration               : Seq[LocationConfig],
    qa                        : Seq[LocationConfig],
    staging                   : Seq[LocationConfig],
    externaltest              : Seq[LocationConfig],
    production                : Seq[LocationConfig],
    zone                      : String,
    platformShutteringEnabled : Boolean
  ) {
    val environments: Map[Environment, Seq[LocationConfig]] = Map(
      Environment.Development  -> development,
      Environment.Integration  -> integration,
      Environment.QA           -> qa,
      Environment.Staging      -> staging,
      Environment.ExternalTest -> externaltest,
      Environment.Production   -> production
    )
  }

  private def yamlConfigReads(key: String): Reads[YamlConfig] = {
    implicit val lcR: Reads[LocationConfig] = LocationConfig.reads

    ( Reads.pure(key)
    ~ (__ \ "environments" \ "development" ).readWithDefault[Seq[LocationConfig]](Seq.empty)
    ~ (__ \ "environments" \ "integration" ).readWithDefault[Seq[LocationConfig]](Seq.empty)
    ~ (__ \ "environments" \ "qa"          ).readWithDefault[Seq[LocationConfig]](Seq.empty)
    ~ (__ \ "environments" \ "staging"     ).readWithDefault[Seq[LocationConfig]](Seq.empty)
    ~ (__ \ "environments" \ "externaltest").readWithDefault[Seq[LocationConfig]](Seq.empty)
    ~ (__ \ "environments" \ "production"  ).readWithDefault[Seq[LocationConfig]](Seq.empty)
    ~ (__ \ "zone"                         ).readWithDefault[String]("public")
    ~ (__ \ "platform-off-switch"          ).readWithDefault[Boolean](true)
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
        (env, locs) <- conf.environments
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
          ruleConfigurationUrl = s"${config.blobUrl}#L${lines.indexWhere(_.contains(s"${conf.service}:")) + 1}",
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
