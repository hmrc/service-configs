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

import play.api.libs.functional.syntax._
import play.api.libs.json.{Json, Format, __}
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}

case class FrontendRoute(
 frontendPath        : String,
 backendPath         : String,
 routesFile          : String = "",
 markerComments      : Set[String] = Set.empty, //All known #! comments found that have been applied to the routes
 shutterKillswitch   : Option[ShutterSwitch] = None,
 shutterServiceSwitch: Option[ShutterSwitch] = None,
 ruleConfigurationUrl: String = "",
 isRegex             : Boolean = false,
 isDevhub            : Boolean = false
)

case class ShutterSwitch(
  switchFile : String,
  statusCode : Option[Int]    = None,
  errorPage  : Option[String] = None,
  rewriteRule: Option[String] = None
)

object ShutterSwitch:
  def fromMongo(m: MongoShutterSwitch): ShutterSwitch =
    ShutterSwitch(m.switchFile, m.statusCode, m.errorPage, m.rewriteRule)

case class FrontendRoutes(
  service     : ServiceName,
  environment : Environment,
  routesFile  : String,
  routes      : Seq[FrontendRoute]
)

object FrontendRoute:
  def fromMongo(mfr: MongoFrontendRoute): FrontendRoute =
    FrontendRoute(
      frontendPath         = mfr.frontendPath,
      backendPath          = mfr.backendPath,
      ruleConfigurationUrl = mfr.ruleConfigurationUrl,
      shutterKillswitch    = mfr.shutterKillswitch.map(ShutterSwitch.fromMongo),
      shutterServiceSwitch = mfr.shutterServiceSwitch.map(ShutterSwitch.fromMongo),
      markerComments       = mfr.markerComments,
      isRegex              = mfr.isRegex,
      isDevhub             = mfr.isDevhub
    )

object FrontendRoutes:

  val format: Format[FrontendRoutes] =
    given Format[ShutterSwitch] = Json.format[ShutterSwitch]
    given Format[FrontendRoute] = Json.format[FrontendRoute]
    ( (__ \ "service"    ).format[ServiceName](ServiceName.format)
    ~ (__ \ "environment").format[Environment](Environment.format)
    ~ (__ \ "routesFile" ).format[String]
    ~ (__ \ "routes"     ).format[Seq[FrontendRoute]]
    )(FrontendRoutes.apply, pt => Tuple.fromProductTyped(pt))

  def fromMongo(mfr: MongoFrontendRoute): FrontendRoutes =
    FrontendRoutes(
      environment = mfr.environment,
      service     = mfr.service,
      routesFile  = mfr.routesFile,
      routes      = Seq(FrontendRoute.fromMongo(mfr))
    )

  def fromMongo(mfrs: Seq[MongoFrontendRoute]): Seq[FrontendRoutes] =
    mfrs
      .groupBy: frs =>
        (frs.service, frs.environment, frs.routesFile)
      .map:
        case ((s, e, rf), v) =>
          ( (s, e, rf),
            v.map(FrontendRoute.fromMongo)
              .foldLeft(
                FrontendRoutes(
                  environment = e,
                  service     = s,
                  routesFile  = rf,
                  routes      = Seq.empty[FrontendRoute]
                )
              ): (frs, fr) =>
                FrontendRoutes(
                  service     = frs.service,
                  environment = frs.environment,
                  routesFile  = frs.routesFile,
                  routes      = Seq(fr) ++ frs.routes
                )
          )
      .values
      .toSeq
