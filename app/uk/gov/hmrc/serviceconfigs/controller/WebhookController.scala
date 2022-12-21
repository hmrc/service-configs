/*
 * Copyright 2022 HM Revenue & Customs
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

import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.service._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import cats.implicits._
import cats.data.EitherT

import play.api.{Configuration, Logging}
import play.api.mvc.ControllerComponents

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class WebhookController @Inject()(
  config                      : Configuration,
  nginxConfig                 : NginxConfig,
  deploymentConfigService     : DeploymentConfigService,
  nginxService                : NginxService,
  routesConfigService         : RoutesConfigService,
  alertConfigSchedulerService : AlertConfigSchedulerService,
  buildJobService             : BuildJobService,
  dashboardService            : DashboardService,
  cc: ControllerComponents
)(implicit ec: ExecutionContext) extends BackendController(cc) with Logging {

  // https://docs.github.com/developers/webhooks-and-events/webhooks/webhook-events-and-payloads#push
  case class Push(repoName: String, branchRef: String)

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  private implicit val readsPush: Reads[Push] =
    ( (__ \ "repository" \ "name").read[String]
    ~ (__ \ "ref"                ).read[String].map(_.stripPrefix("refs/heads/"))
    )(Push.apply _)

  def processGithubWebhook() =
    Action.async(parse.json[Push]) { implicit request =>
      (request.body match {
        case Push(s"app-config-$x",       "main") => EitherT.right[Unit](Environment.parse(x).traverse(deploymentConfigService.update))
        case Push("build-jobs",           "main") => EitherT.right[Unit](buildJobService.updateBuildJobs())
        case Push("grafana-dashboards",   "main") => EitherT.right[Unit](dashboardService.updateGrafanaDashboards())
        case Push("kibana-dashboards",    "main") => EitherT.right[Unit](dashboardService.updateKibanaDashboards())
        case Push(nginxConfig.configRepo, "main") => EitherT.right[Unit](nginxService.update(Environment.values))
        case Push("admin-frontend-proxy", "main") => EitherT.right[Unit](routesConfigService.updateAdminFrontendRoutes())
        case Push("alert-config",         "main") => EitherT.right[Unit](alertConfigSchedulerService.updateConfigs())
        case _                                    => EitherT.left[Unit](Future.unit)
      }).fold(
        _ => { logger.info(s"repo: ${request.body.repoName} branch: ${request.body.branchRef} - no change required")
               Ok(details("Push processed - no change required"))
             },
        _ => { logger.info(s"repo: ${request.body.repoName} branch: ${request.body.branchRef} - successfully processed push")
               Ok(details("Push processed"))
             }
      )
    }

  private def details(msg: String) =
    Json.obj("details" -> msg)

}
