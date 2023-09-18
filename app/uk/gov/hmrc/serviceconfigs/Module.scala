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

package uk.gov.hmrc.serviceconfigs

import com.google.inject.AbstractModule
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRouteParser, NginxConfigParser}
import uk.gov.hmrc.serviceconfigs.notification.{DeadLetterHandler, DeploymentHandler, SlugConfigUpdateHandler}
import uk.gov.hmrc.serviceconfigs.scheduler.{BobbyWarningsNotifierScheduler, ConfigScheduler, MissedWebhookEventsScheduler, ServiceRelationshipScheduler, SlugMetadataUpdateScheduler}

import java.time.Clock

class Module extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[SlugConfigUpdateHandler       ]).asEagerSingleton()
    bind(classOf[DeploymentHandler             ]).asEagerSingleton()
    bind(classOf[DeadLetterHandler             ]).asEagerSingleton()
    bind(classOf[ConfigScheduler               ]).asEagerSingleton()
    bind(classOf[BobbyWarningsNotifierScheduler]).asEagerSingleton()
    bind(classOf[MissedWebhookEventsScheduler  ]).asEagerSingleton()
    bind(classOf[SlugMetadataUpdateScheduler   ]).asEagerSingleton()
    bind(classOf[ServiceRelationshipScheduler  ]).asEagerSingleton()
    bind(classOf[FrontendRouteParser           ]).to(classOf[NginxConfigParser]).asEagerSingleton()
    bind(classOf[Clock                         ]).toInstance(Clock.systemUTC())
  }
}
