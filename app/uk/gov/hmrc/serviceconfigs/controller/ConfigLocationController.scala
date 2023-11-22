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

package uk.gov.hmrc.serviceconfigs.controller

import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.ServiceName
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.persistence._
import uk.gov.hmrc.serviceconfigs.service.AppConfigService

import javax.inject.{Inject, Singleton}
import scala.concurrent.{Future, ExecutionContext}
import uk.gov.hmrc.serviceconfigs.persistence.KibanaDashboardRepository

@Singleton
class ConfigLocationController @Inject()(
  appConfigService                 : AppConfigService
, buildJobRepository               : BuildJobRepository
, kibanaDashboardRepository        : KibanaDashboardRepository
, grafanaDashboardRepository       : GrafanaDashboardRepository
, alertEnvironmentHandlerRepository: AlertEnvironmentHandlerRepository
, serviceManagerConfigRepository   : ServiceManagerConfigRepository
, outagePageRepository             : OutagePageRepository
, upscanConfigRepository           : UpscanConfigRepository
,  mcc: MessagesControllerComponents
)(implicit
  ec: ExecutionContext
) extends BackendController(mcc) {

  import cats.implicits._
  def configLocation(serviceName: ServiceName): Action[AnyContent] = Action.async {
    for {
      appConfigBase  <- appConfigService
                          .appConfigBaseConf(serviceName)
                          .map(x => Map(s"app-config-base" -> x.map(_ => s"https://github.com/hmrc/app-config-base/blob/HEAD/${serviceName.asString}.conf")))
      appConfigEnv   <- Environment
                          .values
                          .foldLeftM[Future, Map[String, Option[String]]](Map.empty) { (acc, env) =>
                            appConfigService
                              .appConfigEnvYaml(env, serviceName)
                              .map(x => acc ++ Map(s"app-config-${env.asString}" -> x.map(_ => s"https://github.com/hmrc/app-config-${env.asString}/blob/HEAD/${serviceName.asString}.yaml")))
                          }
      buildJobs      <- buildJobRepository.findByService(serviceName)
      smConfig       <- serviceManagerConfigRepository.findByService(serviceName)
      kibana         <- kibanaDashboardRepository.findByService(serviceName)
      grafana        <- grafanaDashboardRepository.findByService(serviceName)
      alerts         <- alertEnvironmentHandlerRepository.findByServiceName(serviceName)
      upscan         <- upscanConfigRepository
                          .findByService(serviceName)
                          .map(_.map(u => (s"upscan-config-${u.environment.asString}" -> Some(u.location))).toMap)
      outagePages    <- outagePageRepository
                          .findByServiceName(serviceName)
                          .map(_.getOrElse(Nil).map(env => (s"outage-page-${env.asString}" -> Some(s"https://github.com/hmrc/outage-pages/blob/main/${env.asString}"))).toMap)
      results        =  appConfigBase ++
                        appConfigEnv  ++
                        outagePages   ++
                        upscan        ++
                        Map(
                          "build-jobs"             -> buildJobs.map(_.location)
                        , "service-manager-config" -> smConfig.map(_.location)
                        , "kibana"                 -> kibana.map(_.location)
                        , "grafana"                -> grafana.map(_.location)
                        , "alerts"                 -> alerts.map(_.location)
                        ): Map[String, Option[String]]
    } yield Ok(Json.toJson(results.collect {case (k, Some(v)) => k -> v }))
  }
}
