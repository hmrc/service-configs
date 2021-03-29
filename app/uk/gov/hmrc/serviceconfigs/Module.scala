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

package uk.gov.hmrc.serviceconfigs

import com.google.inject.AbstractModule
import uk.gov.hmrc.serviceconfigs.parser.{FrontendRouteParser, NginxConfigParser}
import uk.gov.hmrc.serviceconfigs.service.{DeadLetterHandler, SlugConfigUpdateHandler}
import uk.gov.hmrc.serviceconfigs.scheduler.{AlertConfigScheduler, FrontendRouteScheduler, SlugMetadataUpdateScheduler}


class Module extends AbstractModule {
  override def configure(): Unit = {
    bind(classOf[SlugConfigUpdateHandler    ]).asEagerSingleton()
    bind(classOf[DeadLetterHandler          ]).asEagerSingleton()
    bind(classOf[SlugMetadataUpdateScheduler]).asEagerSingleton()
    bind(classOf[FrontendRouteScheduler     ]).asEagerSingleton()
    bind(classOf[AlertConfigScheduler       ]).asEagerSingleton()
    bind(classOf[FrontendRouteParser        ]).to(classOf[NginxConfigParser]).asEagerSingleton()
  }
}
