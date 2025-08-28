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

package uk.gov.hmrc.serviceconfigs.service

import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.connector.ReleasesApiConnector
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, ServiceName, SlugDependency, SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository}
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugConfigurationService @Inject()(
  releasesApiConnector       : ReleasesApiConnector
, slugInfoRepository         : SlugInfoRepository
, dependencyConfigRepository : DependencyConfigRepository
)(using ec: ExecutionContext) extends Logging:

  private def classpathOrderedDependencies(slugInfo: SlugInfo): List[SlugDependency] =
    slugInfo
      .classpath
      .split(":")
      .map(_.replace("$lib_dir/", s"${slugInfo.name.asString}-${slugInfo.version}/lib/"))
      .toList
      .flatMap(path => slugInfo.dependencies.filter(_.path == path))

  def addSlugInfo(slugInfo: SlugInfo): Future[Unit] =
    for
      oRelease <- releasesApiConnector.getWhatsRunningWhere(slugInfo.name)(using HeaderCarrier())
      _        <- slugInfoRepository.add(
                    slugInfo.copy(dependencies = classpathOrderedDependencies(slugInfo))
                  , environments = oRelease.fold(Set.empty):
                                     _.deployments
                                       .collect:
                                         case d if d.version == slugInfo.version => d.environment
                                       .toSet
                  )
    yield ()

  def deleteSlugInfo(serviceName: ServiceName, slugVersion: Version): Future[Unit] =
    slugInfoRepository.delete(serviceName, slugVersion)

  def addDependencyConfigurations(dependencyConfigs: Seq[DependencyConfig]): Future[Unit] =
    dependencyConfigs.toList.traverse_(dependencyConfigRepository.add)

  def getSlugInfo(serviceName: ServiceName, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(serviceName, flag)

  def findDependencyConfig(group: String, artefact: String, version: String): Future[Option[DependencyConfig]] =
    dependencyConfigRepository.getDependencyConfig(group, artefact, version)
