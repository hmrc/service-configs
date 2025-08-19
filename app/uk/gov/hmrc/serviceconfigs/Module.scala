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

import play.api.inject.Binding
import play.api.{Configuration, Environment, Logger}
import uk.gov.hmrc.serviceconfigs.notification.{DeploymentDeadLetterHandler, DeploymentHandler, SlugDeadLetterHandler, SlugHandler}
import uk.gov.hmrc.serviceconfigs.parser.{NginxRouteParser, NginxConfigParser}
import uk.gov.hmrc.serviceconfigs.scheduler._

import java.time.Clock

class Module extends play.api.inject.Module:

  private val logger = Logger(getClass)

  private def ecsDeploymentsBindings(configuration: Configuration): Seq[Binding[_]] =
    if configuration.get[Boolean]("aws.sqs.enabled") then
      Seq(
        bind[DeploymentHandler          ].toSelf.eagerly()
      , bind[DeploymentDeadLetterHandler].toSelf.eagerly()
      , bind[SlugHandler                ].toSelf.eagerly()
      , bind[SlugDeadLetterHandler      ].toSelf.eagerly()
      )
    else
      logger.warn("SQS handlers are disabled")
      Seq.empty

  override def bindings(environment: Environment, configuration: Configuration): Seq[Binding[_]] =
    Seq(
      bind[ConfigScheduler             ].toSelf.eagerly()
    , bind[MissedWebhookEventsScheduler].toSelf.eagerly()
    , bind[SlugMetadataUpdateScheduler ].toSelf.eagerly()
    , bind[ServiceRelationshipScheduler].toSelf.eagerly()
    , bind[NginxRouteParser            ].to[NginxConfigParser].eagerly()
    , bind[Clock                       ].toInstance(Clock.systemUTC())
    ) ++
    ecsDeploymentsBindings(configuration)
