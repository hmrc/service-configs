/*
 * Copyright 2021 HM Revenue & Customs
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
import javax.inject.Inject
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, MongoLockService}
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.service.NginxService
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt

class FrontendRouteScheduler @Inject()(
  nginxConfig        : NginxConfig,
  nginxService       : NginxService,
  configuration      : Configuration,
  schedulerConfigs   : SchedulerConfigs,
  mongoLockRepository: MongoLockRepository
  )(implicit actorSystem: ActorSystem,
    applicationLifecycle: ApplicationLifecycle,
    ec: ExecutionContext
  ) extends SchedulerUtils {

  private val environments =
    List(
      "production",
      "externaltest",
      "qa",
      "staging",
      "integration",
      "development")

  scheduleWithLock(
    label           = "frontendRoutes"
  , schedulerConfig = schedulerConfigs.frontendRoutesReload
  , lock            = MongoLockService(mongoLockRepository, "service-configs-sync-job", 20.minutes)
  ){
    nginxService.update(environments)
      .map(_ => ())
  }
}
