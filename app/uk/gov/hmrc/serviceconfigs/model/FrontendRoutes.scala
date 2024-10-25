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

package uk.gov.hmrc.serviceconfigs.model

import play.api.libs.json.{Json, Writes, __}
import play.api.libs.functional.syntax._
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}

case class ShutterSwitch(
  switchFile : String,
  statusCode : Option[Int]    = None,
  errorPage  : Option[String] = None,
  rewriteRule: Option[String] = None
)

object ShutterSwitch:
  def fromMongo(m: MongoShutterSwitch): ShutterSwitch =
    ShutterSwitch(m.switchFile, m.statusCode, m.errorPage, m.rewriteRule)

case class NginxRouteConfig(
  frontendPath        : String,
  backendPath         : String,
  routesFile          : String                = "",
  markerComments      : Set[String]           = Set.empty, //All known #! comments found that have been applied to the routes
  shutterKillswitch   : Option[ShutterSwitch] = None,
  shutterServiceSwitch: Option[ShutterSwitch] = None,
  ruleConfigurationUrl: String                = "",
  isRegex             : Boolean               = false,
  isDevhub            : Boolean               = false
)

object NginxRouteConfig:
  def fromMongo(mfr: MongoFrontendRoute): NginxRouteConfig =
    NginxRouteConfig(
      frontendPath         = mfr.frontendPath,
      backendPath          = mfr.backendPath,
      ruleConfigurationUrl = mfr.ruleConfigurationUrl,
      shutterKillswitch    = mfr.shutterKillswitch.map(ShutterSwitch.fromMongo),
      shutterServiceSwitch = mfr.shutterServiceSwitch.map(ShutterSwitch.fromMongo),
      markerComments       = mfr.markerComments,
      isRegex              = mfr.isRegex,
      isDevhub             = mfr.isDevhub
    )

case class ShutteringRoutes(
  service    : ServiceName,
  environment: Environment,
  routesFile : String,
  routes     : Seq[NginxRouteConfig]
)

object ShutteringRoutes:
  val writes: Writes[ShutteringRoutes] =
    given Writes[ShutterSwitch]    = Json.writes[ShutterSwitch]
    given Writes[NginxRouteConfig] = Json.writes[NginxRouteConfig]
    ( (__ \ "service"    ).write[ServiceName](ServiceName.format)
    ~ (__ \ "environment").write[Environment](Environment.format)
    ~ (__ \ "routesFile" ).write[String]
    ~ (__ \ "routes"     ).write[Seq[NginxRouteConfig]]
    )(pt => Tuple.fromProductTyped(pt))

  def fromMongo(mfrs: Seq[MongoFrontendRoute]): Seq[ShutteringRoutes] =
    mfrs
      .groupBy: frs =>
        (frs.service, frs.environment, frs.routesFile)
      .map:
        case ((s, e, rf), v) =>
          ((s, e, rf),
            v.map(NginxRouteConfig.fromMongo)
              .foldLeft(
                ShutteringRoutes(
                  environment = e,
                  service     = s,
                  routesFile  = rf,
                  routes      = Seq.empty[NginxRouteConfig]
                )
              ): (frs, fr) =>
                ShutteringRoutes(
                  service     = frs.service,
                  environment = frs.environment,
                  routesFile  = frs.routesFile,
                  routes      = Seq(fr) ++ frs.routes
                )
          )
      .values
      .toSeq

case class Route(
  serviceName         : ServiceName
, path                : String
, ruleConfigurationUrl: Option[String]
, isRegex             : Boolean = false
, routeType           : RouteType
, environment         : Environment
)

object Route:
  val writes: Writes[Route] =
    ( (__ \ "serviceName"         ).write[ServiceName](ServiceName.format)
    ~ (__ \ "path"                ).write[String]
    ~ (__ \ "ruleConfigurationUrl").writeNullable[String]
    ~ (__ \ "isRegex"             ).write[Boolean]
    ~ (__ \ "routeType"           ).write[RouteType](RouteType.writes)
    ~ (__ \ "environment"         ).write[Environment](Environment.format)
    )(r => Tuple.fromProductTyped(r))

  def fromMongo(mfr: MongoFrontendRoute): Route =
    Route(
      serviceName          = mfr.service,
      path                 = mfr.frontendPath,
      ruleConfigurationUrl = Some(mfr.ruleConfigurationUrl),
      isRegex              = mfr.isRegex,
      routeType            = if mfr.isDevhub then RouteType.Devhub else RouteType.Frontend,
      environment          = mfr.environment
    )
