/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.service

import cats.implicits._
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.persistence.{DeploymentConfigRepository, LatestConfigRepository, ResourceUsageRepository}
import uk.gov.hmrc.serviceconfigs.persistence.ResourceUsageRepository.PlanOfWork

import java.time.Instant
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ResourceUsageService @Inject()(
  latestConfigRepository    : LatestConfigRepository
, deploymentConfigRepository: DeploymentConfigRepository
, resourceUsageRepository   : ResourceUsageRepository
)(implicit
  ec : ExecutionContext
) {
  def populate(date: Instant): Future[Unit] =
    Environment.values.foldLeftM[Future, Unit](())((_, environment) =>
      for {
        deploymentConfigs             <- deploymentConfigRepository.find(applied = true, environments = Seq(environment))
        latestSnapshots               <- resourceUsageRepository.latestSnapshotsInEnvironment(environment)
        planOfWork                    =  PlanOfWork.fromLatestSnapshotsAndCurrentDeploymentConfigs(latestSnapshots.toList, deploymentConfigs.toList, date)
        _                             <- resourceUsageRepository.executePlanOfWork(planOfWork, environment)
        latestConfigs                 <- latestConfigRepository.findAll("app-config-" + environment)
        undeployedAndDeletedSnapshots =  latestSnapshots.filter(snapshot =>
                                           deploymentConfigs.find(_.serviceName == snapshot.serviceName).exists(_.instances == 0) && // check for no aws usage in applied config
                                           !latestConfigs.exists(_.fileName == snapshot.serviceName.asString + ".yaml")              // check for deleted github config
                                         )
        _                             =  resourceUsageRepository.setLatestFlag(latest = false, undeployedAndDeletedSnapshots)
      } yield ()
    )
}
