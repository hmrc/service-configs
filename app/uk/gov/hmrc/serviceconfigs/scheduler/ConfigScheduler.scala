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
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, TimePeriodLockService}
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.service.{AlertConfigService, AppConfigCommonService, AppConfigEnvService, BobbyRulesService}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository

import javax.inject.{Inject, Singleton}
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt

@Singleton
class ConfigScheduler @Inject()(
  configuration                     : Configuration,
  schedulerConfigs                  : SchedulerConfigs,
  mongoLockRepository               : MongoLockRepository,
  deploymentConfigSnapshotRepository: DeploymentConfigSnapshotRepository,
  alertConfigService                : AlertConfigService,
  appConfigCommonService            : AppConfigCommonService,
  appConfigEnvService               : AppConfigEnvService,
  bobbyRulesService                 : BobbyRulesService
)(implicit
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils with Logging {

  scheduleWithTimePeriodLock(
    label           = "ConfigScheduler",
    schedulerConfig = schedulerConfigs.configScheduler,
    lock            = TimePeriodLockService(mongoLockRepository, "config-scheduler", schedulerConfigs.configScheduler.interval.minus(1.minutes))
  ) {
    logger.info("Updating config")
    for {
      _ <- run("snapshot Deployments"  , deploymentConfigSnapshotRepository.populate(Instant.now()))
      _ <- run("update Alert Handlers" , alertConfigService.updateConfigs())
      _ <- run("AppConfigCommonUpdater", appConfigCommonService.update())
      _ <- run("AppConfigEnvUpdater"   , appConfigEnvService.update())
      _ <- run("BobbyRulesUpdater"     , bobbyRulesService.update())
    } yield logger.info("Finished updating config")
  }

  private def run(name: String, f: Future[Unit]) = {
    logger.info(s"Starting scheduled task: $name")
    f.map { x => logger.info(s"Successfully run scheduled task: $name"); x }
     .recover { case e => logger.error(s"Error running scheduled task $name", e) }
  }
}
