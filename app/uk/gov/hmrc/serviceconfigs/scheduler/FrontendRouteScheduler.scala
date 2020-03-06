/*
 * Copyright 2020 HM Revenue & Customs
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

import akka.actor.{Actor, ActorSystem, Props}
import javax.inject.Inject
import play.api.{Configuration, Logger}
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.service.NginxService
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.persistence.MongoLock

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class FrontendRouteScheduler @Inject()(
  nginxConfig  : NginxConfig,
  nginxService : NginxService,
  configuration: Configuration,
  schedulerConfigs: SchedulerConfigs,
  mongoLock        : MongoLock
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
  , lock            = mongoLock
  ){
    nginxService.update(environments)
      .map(_ => ())
  }
}
