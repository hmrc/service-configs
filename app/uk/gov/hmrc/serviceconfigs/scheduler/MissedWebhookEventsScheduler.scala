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

import org.apache.pekko.actor.ActorSystem
import play.api.Logging
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.service._

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class MissedWebhookEventsScheduler @Inject()(
  schedulerConfigs           : SchedulerConfigs,
  mongoLockRepository        : MongoLockRepository,
  appConfigService           : AppConfigService,
  bobbyRulesService          : BobbyRulesService,
  buildJobService            : BuildJobService,
  dashboardService           : DashboardService,
  nginxService               : NginxService,
  routesConfigService        : RoutesConfigService,
  serviceManagerConfigService: ServiceManagerConfigService,
  outagePageService          : OutagePageService,
  timestampSupport           : TimestampSupport
)(implicit
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils with Logging {

  scheduleWithTimePeriodLock(
    label           = "MissedWebhookEventsScheduler",
    schedulerConfig = schedulerConfigs.missedWebhookEventsScheduler,
    lock            = ScheduledLockService(mongoLockRepository, "missed-webhook-events", timestampSupport, schedulerConfigs.missedWebhookEventsScheduler.interval)
  ) {
    logger.info("Updating incase of missed webhook event")
    runAllAndFailWithFirstError(
      for {
        _ <- accumulateErrors("update Build Jobs"            , buildJobService.updateBuildJobs())
        _ <- accumulateErrors("update Granfan Dashboards"    , dashboardService.updateGrafanaDashboards())
        _ <- accumulateErrors("update Kibana Dashdoards"     , dashboardService.updateKibanaDashboards())
        _ <- accumulateErrors("update Frontend Routes"       , nginxService.update(Environment.values))
        _ <- accumulateErrors("update Admin Frountend Routes", routesConfigService.updateAdminFrontendRoutes())
        _ <- accumulateErrors("AppConfigBaseUpdater"         , appConfigService.updateAppConfigBase())
        _ <- accumulateErrors("AppConfigCommonUpdater"       , appConfigService.updateAppConfigCommon())
        _ <- accumulateErrors("AppConfigEnvUpdater"          , appConfigService.updateAllAppConfigEnv())
        _ <- accumulateErrors("update Service Manager Config", serviceManagerConfigService.update())
        _ <- accumulateErrors("BobbyRulesUpdater"            , bobbyRulesService.update())
        _ <- accumulateErrors("OutagePageUpdater"            , outagePageService.update())
      } yield logger.info("Finished updating incase of missed webhook event")
    )
  }
}
