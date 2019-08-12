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

package uk.gov.hmrc.serviceconfigs

import akka.actor.{Actor, ActorSystem, Props}
import javax.inject.Inject
import play.api.{Configuration, Logger}
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.service.NginxService

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class FrontendRouteScheduler @Inject()(actorSystem: ActorSystem,
                                       nginxConfig: NginxConfig,
                                       nginxService: NginxService,
                                       configuration: Configuration) {

  private val props = Props.create(classOf[FrontendRouteActor], nginxService)
  private val frontendRouteActor = actorSystem.actorOf(props)

  if(nginxConfig.schedulerEnabled) {
    Logger.info("Starting frontend route scheduler")
    actorSystem.scheduler.schedule(1.seconds, nginxConfig.schedulerDelay.minutes, frontendRouteActor, "tick")
  }
  else {
    Logger.info("Frontend route scheduler is DISABLED. To enabled set 'nginx.reload.enabled' as true.")
  }

}


class FrontendRouteActor(nginxService: NginxService)  extends Actor {

  private val environments = List("production", "qa", "staging", "development")

  override def receive: Receive = {
    case _ => nginxService.update(environments)
  }
}