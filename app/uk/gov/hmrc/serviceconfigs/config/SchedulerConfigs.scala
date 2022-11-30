/*
 * Copyright 2022 HM Revenue & Customs
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

object SchedulerConfig {
  def apply(
      configuration   : Configuration
    , schedulerKey    : String
    ): SchedulerConfig = {
      val enabledKey      = s"$schedulerKey.enabled"
      val intervalKey     = s"$schedulerKey.interval"
      val initialDelayKey = s"$schedulerKey.initialDelay"
      SchedulerConfig(
          enabledKey   = enabledKey
        , enabled      = configuration.get[Boolean](enabledKey)
        , interval     = configuration.get[FiniteDuration](intervalKey)
        , initialDelay = configuration.get[FiniteDuration](initialDelayKey)
        )
    }
}

@Singleton
class SchedulerConfigs @Inject()(configuration: Configuration) {
  val frontendRoutesReload             = SchedulerConfig(configuration, "nginx.reload")
  val slugMetadataUpdate               = SchedulerConfig(configuration, "slug-metadata-scheduler")
  val alertConfigUpdate                = SchedulerConfig(configuration, "alert-config-scheduler")
  val deploymentConfigUpdate           = SchedulerConfig(configuration, "deployment-config-scheduler")
  val deploymentConfigSnapshotPopulate = SchedulerConfig(configuration, "deployment-config-snapshot-scheduler")
  val dashboardUpdate                  = SchedulerConfig(configuration, "dashboard-scheduler")
  val buildJobUpdate                   = SchedulerConfig(configuration, "build-jobs-scheduler")
}
