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
import play.api.{Configuration, Logging}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.{LockService, MongoLockRepository}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.service._

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

@Singleton
class MissedWebhookEventsScheduler @Inject()(
  configuration                     : Configuration,
  schedulerConfigs                  : SchedulerConfigs,
  mongoLockRepository               : MongoLockRepository,
  deploymentConfigService           : DeploymentConfigService,
  nginxService                      : NginxService,
  routesConfigService               : RoutesConfigService,
  buildJobService                   : BuildJobService,
  dashboardService                  : DashboardService
)(implicit
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils with Logging {

  scheduleWithLock(
    label           = "MissedWebhookEventsScheduler",
    schedulerConfig = schedulerConfigs.missedWebhookEventsScheduler,
    lock            = LockService(mongoLockRepository, "missed-webhook-events", 20.minutes)
  ) {
    logger.info("Updating encase of missed webhook event")
    for {
      _ <- run("update Deployments",            deploymentConfigService.updateAll())
      _ <- run("update Build Jobs",             buildJobService.updateBuildJobs())
      _ <- run("update Granfan Dashboards",     dashboardService.updateGrafanaDashboards())
      _ <- run("update Kibana Dashdoards",      dashboardService.updateKibanaDashboards())
      _ <- run("update Frontend Routes",        nginxService.update(Environment.values))
      _ <- run("update Admin Frountend Routes", routesConfigService.updateAdminFrontendRoutes())
    } yield logger.info("Finished updating encase of missed webhook event")
  }

  private def run(name: String, f: Future[Unit]) = {
    logger.info(s"Starting scheduled task: $name")
    f.map { x => logger.info(s"Successfully run scheduled task: $name"); x }
     .recover { case e => logger.error(s"Error running scheduled task $name", e) }
  }
}