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

package uk.gov.hmrc.serviceconfigs.config

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import scala.concurrent.duration.FiniteDuration

case class SchedulerConfig(
  enabledKey  : String
, enabled     : Boolean
, interval    : FiniteDuration
, initialDelay: FiniteDuration
)

object SchedulerConfig:
  def apply(
    configuration   : Configuration
  , schedulerKey    : String
  ): SchedulerConfig =
    SchedulerConfig(
      enabledKey   = s"$schedulerKey.enabled"
    , enabled      = configuration.get[Boolean]( s"$schedulerKey.enabled")
    , interval     = configuration.get[FiniteDuration](s"$schedulerKey.interval")
    , initialDelay = configuration.get[FiniteDuration](s"$schedulerKey.initialDelay")
    )

@Singleton
class SchedulerConfigs @Inject()(configuration: Configuration):
  val configScheduler                           = SchedulerConfig(configuration, "config-scheduler")
  val missedWebhookEventsScheduler              = SchedulerConfig(configuration, "missed-webhook-events-scheduler")
  val slugMetadataScheduler                     = SchedulerConfig(configuration, "slug-metadata-scheduler")
  val serviceRelationshipScheduler              = SchedulerConfig(configuration, "service-relationship-scheduler")
  val deprecationWarningsNotificationScheduler  = SchedulerConfig(configuration, "deprecation-warnings-notification-scheduler")
  val serviceToRepoNameScheduler                = SchedulerConfig(configuration, "service-to-repo-name-scheduler")
