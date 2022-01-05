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

package uk.gov.hmrc.serviceconfigs.service

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfigSnapshot, Environment}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigSnapshotRepository

import java.time.LocalDateTime
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ResourceUsageService @Inject() (
  deploymentConfigSnapshotRepository: DeploymentConfigSnapshotRepository)(implicit ec: ExecutionContext) {

  def resourceUsageForService(name: String): Future[Seq[ResourceUsage]] =
    deploymentConfigSnapshotRepository
      .snapshotsForService(name)
      .map(_.map(ResourceUsage.fromDeploymentConfigSnapshot))
}

final case class ResourceUsage(
  date: LocalDateTime,
  serviceName: String,
  environment: Environment,
  slots: Int,
  instances: Int
)

object ResourceUsage {

  def fromDeploymentConfigSnapshot(deploymentConfigSnapshot: DeploymentConfigSnapshot): ResourceUsage = {
    val slots =
      deploymentConfigSnapshot.deploymentConfig.map(_.slots).getOrElse(0)

    val instances =
      deploymentConfigSnapshot.deploymentConfig.map(_.instances).getOrElse(0)

    ResourceUsage(
      deploymentConfigSnapshot.date,
      deploymentConfigSnapshot.name,
      deploymentConfigSnapshot.environment,
      slots,
      instances
    )
  }

  val apiFormat: Format[ResourceUsage] =
    (   (__ \ "date"             ).format[LocalDateTime]
      ~ (__ \ "serviceName"      ).format[String]
      ~ (__ \ "environment"      ).format[Environment](Environment.format)
      ~ (__ \ "slots"            ).format[Int]
      ~ (__ \ "instances"        ).format[Int]
      ) (ResourceUsage.apply, unlift(ResourceUsage.unapply))
}