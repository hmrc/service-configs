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

import javax.inject.{Inject, Singleton}
import play.api.inject.ApplicationLifecycle
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.mongo.lock.{MongoLockRepository, ScheduledLockService}
import uk.gov.hmrc.serviceconfigs.connector.ReleasesApiConnector
import uk.gov.hmrc.serviceconfigs.service.ConfigService
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentEventRepository

import scala.concurrent.ExecutionContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfig

@Singleton
class UpdateDeploymentConfigChangeScheduler @Inject()(
  config                   : Configuration,
  mongoLockRepository      : MongoLockRepository,
  timestampSupport         : TimestampSupport,
  configService            : ConfigService,
  releasesApiConnector     : ReleasesApiConnector,
  deploymentEventRepository: DeploymentEventRepository,
)(using
  actorSystem         : ActorSystem,
  applicationLifecycle: ApplicationLifecycle,
  ec                  : ExecutionContext
) extends SchedulerUtils:

  private given HeaderCarrier = HeaderCarrier()

  import cats.implicits._

  private val schedulerConfig =
    SchedulerConfig(config, "update-deployment-config-change-scheduler")

  scheduleWithTimePeriodLock(
    label           = "UpdateDeploymentConfigChangeScheduler",
    schedulerConfig = schedulerConfig,
    lock            = ScheduledLockService(mongoLockRepository, "UpdateDeploymentConfigChangeScheduler", timestampSupport, schedulerConfig.interval)
  ):
    val(from, to) =
      val now  = Instant.now()
      ( now.minus(90, ChronoUnit.DAYS)
      , now
      )
    logger.info(s"Updating deployment config change data for range - $from  - $to")
    releasesApiConnector
      .getWhatsRunningWhere()
      .flatMap: releases =>
        releases
          .sortBy(_.serviceName.asString)
          .flatMap(_.deployments)
          .filterNot(_.environment == Environment.Integration) // Not shown in timeline
          .foldLeftM(()): (_, d) =>
            val env = d.environment
            for
              pag <- releasesApiConnector
                      .deploymentHistory(env, from, to, d.serviceName)
              _   =  logger.info(s"Updating deployment ${d.serviceName.asString} in ${env.asString} historic events ${pag.history.size}")
              _   <- pag.history.foldLeftM(()):
                      case (_, deployment) =>
                        for
                          o <- configService
                                .configChanges(deployment.deploymentId, None)
                                .map:
                                  case Right(x)  => Some((x.configChanges.nonEmpty, x.deploymentChanges.nonEmpty))
                                  case Left(msg) => logger.error(s"Error processing $deployment: $msg"); None
                          _ <- deploymentEventRepository
                                .put(DeploymentEventRepository.DeploymentEvent(
                                  serviceName             = deployment.serviceName
                                , environment             = env
                                , version                 = deployment.version
                                , deploymentId            = deployment.deploymentId
                                , configChanged           = o.map(_._1)
                                , deploymentConfigChanged = o.map(_._2)
                                , configId                = Some(deployment.configId)
                                , time                    = deployment.lastDeployed
                                ))
                        yield ()
            yield ()
      .map(_ => logger.info(s"Finished updating deployment config change data for range - $from  - $to"))
