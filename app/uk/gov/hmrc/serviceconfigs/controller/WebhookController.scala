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

import cats.implicits.*
import cats.data.EitherT
import play.api.Logging
import play.api.mvc.{Action, ControllerComponents}
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.service.*
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class WebhookController @Inject()(
  nginxConfig                : NginxConfig,
  appConfigService           : AppConfigService,
  bobbyRulesService          : BobbyRulesService,
  internalAuthConfigService  : InternalAuthConfigService,
  nginxService               : NginxService,
  routesConfigService        : RoutesConfigService,
  buildJobService            : BuildJobService,
  dashboardService           : DashboardService,
  outagePageService          : OutagePageService,
  serviceManagerConfigService: ServiceManagerConfigService,
  upscanConfigService        : UpscanConfigService,
  cc                         : ControllerComponents
)(using
  ec: ExecutionContext
) extends BackendController(cc)
     with Logging:

  // https://docs.github.com/developers/webhooks-and-events/webhooks/webhook-events-and-payloads#push
  case class Push(repoName: String, branchRef: String)

  import play.api.libs.functional.syntax._
  import play.api.libs.json._
  private given Reads[Push] =
    ( (__ \ "repository" \ "name").read[String]
    ~ (__ \ "ref"                ).read[String].map(_.stripPrefix("refs/heads/"))
    )(Push.apply _)

  val processGithubWebhook: Action[Push] =
    Action(parse.json[Push]):
      implicit request =>
        (request.body match
          case Push("alert-config"           , "main") => EitherT.left[Unit](Future.unit) // this is pulled from Artifactory
          case Push("app-config-base"        , "main") => EitherT.right[Unit](appConfigService.updateAppConfigBase())
          case Push("app-config-common"      , "main") => EitherT.right[Unit](appConfigService.updateAppConfigCommon())
          case Push(s"app-config-$x"         , "main") => EitherT.right[Unit](Environment.parse(x).traverse(appConfigService.updateAppConfigEnv))
          case Push("bobby-config"           , "main") => EitherT.right[Unit](bobbyRulesService.update())
          case Push("build-jobs"             , "main") => EitherT.right[Unit](buildJobService.updateBuildJobs())
          case Push("internal-auth-config"   , "main") => EitherT.right[Unit](internalAuthConfigService.updateInternalAuth())
          case Push("grafana-dashboards"     , "main") => EitherT.right[Unit](dashboardService.updateGrafanaDashboards())
          case Push("kibana-dashboards"      , "main") => EitherT.right[Unit](dashboardService.updateKibanaDashboards())
          case Push(nginxConfig.configRepo   , "main") => EitherT.right[Unit](nginxService.update(Environment.values.toList))
          case Push("admin-frontend-proxy"   , "main") => EitherT.right[Unit](routesConfigService.updateAdminFrontendRoutes())
          case Push("outage-pages"           , "main") => EitherT.right[Unit](outagePageService.update())
          case Push("service-manager-config" , "main") => EitherT.right[Unit](serviceManagerConfigService.update())
          case Push("upscan-app-config"      , "main") => EitherT.right[Unit](upscanConfigService.update())
          case _                                       => EitherT.left[Unit](Future.unit)
        ).fold(
          _ => logger.info(s"repo: ${request.body.repoName} branch: ${request.body.branchRef} - no change required")
        , _ => logger.info(s"repo: ${request.body.repoName} branch: ${request.body.branchRef} - successfully processed push")
        ).recover:
          case NonFatal(ex) => logger.error(s"Push failed with error: ${ex.getMessage}", ex)
        Accepted(details("Push accepted"))

  private def details(msg: String) =
    Json.obj("details" -> msg)
