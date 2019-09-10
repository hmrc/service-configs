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

package uk.gov.hmrc.serviceconfigs.model

import play.api.libs.json.Json
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}

case class FrontendRoute(
 frontendPath: String,
 backendPath: String,
 routesFile: String = "",
 isShutterable: Boolean = true, //True unless the route contains the special #NOT_SHUTTERABLE comment
 shutterKillswitch: Option[ShutterSwitch] = None,
 shutterServiceSwitch: Option[ShutterSwitch] = None,
 ruleConfigurationUrl: String = "",
 isRegex: Boolean = false
)

case class ShutterSwitch(switchFile: String, statusCode: Option[Int] = None, errorPage: Option[String] = None, rewriteRule: Option[String] = None)

object ShutterSwitch {
  def fromMongo(m: MongoShutterSwitch) = ShutterSwitch(m.switchFile, m.statusCode, m.errorPage, m.rewriteRule)
}


case class FrontendRoutes(
  service     : String,
  environment : String,
  routesFile  : String,
  routes      : Seq[FrontendRoute])

object FrontendRoute {

  implicit val formatShutterSwitch = Json.format[ShutterSwitch]
  implicit val formats = Json.format[FrontendRoute]

  def fromMongo(mfr: MongoFrontendRoute) : FrontendRoute =
    FrontendRoute(
      frontendPath         = mfr.frontendPath,
      backendPath          = mfr.backendPath,
      ruleConfigurationUrl = mfr.ruleConfigurationUrl,
      shutterKillswitch    = mfr.shutterKillswitch.map(ShutterSwitch.fromMongo),
      shutterServiceSwitch = mfr.shutterServiceSwitch.map(ShutterSwitch.fromMongo),
      isShutterable        = mfr.isShutterable,
      isRegex              = mfr.isRegex)
}

object FrontendRoutes {

  implicit val formats = Json.using[Json.WithDefaultValues].format[FrontendRoutes]

  def fromMongo(mfr: MongoFrontendRoute) : FrontendRoutes =
    FrontendRoutes(
      environment = mfr.environment,
      service     = mfr.service,
      routesFile  = mfr.routesFile,
      routes      = Seq(FrontendRoute.fromMongo(mfr)))

  def fromMongo(mfrs: Seq[MongoFrontendRoute]): Seq[FrontendRoutes] =
    mfrs.groupBy(frs => (frs.service, frs.environment, frs.routesFile))
      .map {
        case ((s, e, rf), v) => ((s, e, rf), v.map(FrontendRoute.fromMongo)
                            .foldLeft(FrontendRoutes(
                              environment = e,
                              service     = s,
                              routesFile  = rf,
                              routes      = Seq[FrontendRoute]()
                            ))((frs, fr) => FrontendRoutes(
                                              service     = frs.service,
                                              environment = frs.environment,
                                              routesFile  = frs.routesFile,
                                              routes      = Seq(fr) ++ frs.routes)))
      }.values.toSeq

}