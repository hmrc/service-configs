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
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute


case class FrontendRoute(
  frontendPath        : String,
  backendPath         : String,
  ruleConfigurationUrl: String = "",
  isRegex             : Boolean = false)

case class FrontendRoutes(
  service    : String,
  environment: String,
  routes     : Seq[FrontendRoute])

object FrontendRoute {

  implicit val formats = Json.format[FrontendRoute]

  def fromMongo(mfr: MongoFrontendRoute) : FrontendRoute =
    FrontendRoute(
      frontendPath         = mfr.frontendPath,
      backendPath          = mfr.backendPath,
      ruleConfigurationUrl = mfr.ruleConfigurationUrl,
      isRegex              = mfr.isRegex)
}

object FrontendRoutes {

  implicit val formats = Json.using[Json.WithDefaultValues].format[FrontendRoutes]

  def fromMongo(mfr: MongoFrontendRoute) : FrontendRoutes =
    FrontendRoutes(
      environment = mfr.environment,
      service     = mfr.service,
      routes      = Seq(FrontendRoute.fromMongo(mfr)))

  def fromMongo(mfrs: Seq[MongoFrontendRoute]): Seq[FrontendRoutes] =
    mfrs.groupBy(frs => (frs.service, frs.environment))
      .map {
        case ((s, e), v) => ((s, e), v.map(FrontendRoute.fromMongo)
                            .foldLeft(FrontendRoutes(
                              environment = e,
                              service     = s,
                              routes      = Seq[FrontendRoute]()
                            ))((frs, fr) => FrontendRoutes(
                                              service     = frs.service,
                                              environment = frs.environment,
                                              routes      = Seq(fr) ++ frs.routes)))
      }.values.toSeq

}