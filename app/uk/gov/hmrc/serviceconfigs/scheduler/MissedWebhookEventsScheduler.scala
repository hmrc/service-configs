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

package uk.gov.hmrc.serviceconfigs.scheduler

import akka.actor.ActorSystem
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, TimePeriodLockService}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.service._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

@Singleton
class MissedWebhookEventsScheduler @Inject()(
  schedulerConfigs        : SchedulerConfigs,
  mongoLockRepository     : MongoLockRepository,
  appConfigCommonService  : AppConfigCommonService,
  appConfigEnvService     : AppConfigEnvService,
  bobbyRulesService       : BobbyRulesService,
  buildJobService         : BuildJobService,
  dashboardService        : DashboardService,
  deploymentConfigService : DeploymentConfigService,
  nginxService            : NginxService,
  routesConfigService     : RoutesConfigService
)(implicit
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils with Logging {

  scheduleWithTimePeriodLock(
    label           = "MissedWebhookEventsScheduler",
    schedulerConfig = schedulerConfigs.missedWebhookEventsScheduler,
    lock            = TimePeriodLockService(mongoLockRepository, "missed-webhook-events", schedulerConfigs.missedWebhookEventsScheduler.interval.minus(1.minutes))
  ) {
    logger.info("Updating incase of missed webhook event")
    for {
      _ <- run("update Deployments"           , deploymentConfigService.updateAll())
      _ <- run("update Build Jobs"            , buildJobService.updateBuildJobs())
      _ <- run("update Granfan Dashboards"    , dashboardService.updateGrafanaDashboards())
      _ <- run("update Kibana Dashdoards"     , dashboardService.updateKibanaDashboards())
      _ <- run("update Frontend Routes"       , nginxService.update(Environment.values))
      _ <- run("update Admin Frountend Routes", routesConfigService.updateAdminFrontendRoutes())
      _ <- run("AppConfigCommonUpdater"       , appConfigCommonService.update())
      _ <- run("AppConfigEnvUpdater"          , appConfigEnvService.update())
      _ <- run("BobbyRulesUpdater"            , bobbyRulesService.update())
    } yield logger.info("Finished updating incase of missed webhook event")
  }

  private def run(name: String, f: Future[Unit]) = {
    logger.info(s"Starting scheduled task: $name")
    f.map { x => logger.info(s"Successfully run scheduled task: $name"); x }
     .recover { case e => logger.error(s"Error running scheduled task $name", e) }
  }
}
