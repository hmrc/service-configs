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

import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, SlugDependency, SlugInfo, SlugInfoFlag}
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository, SlugVersionRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugConfigurationService @Inject()(
  slugInfoRepository         : SlugInfoRepository,
  slugVersionRepository      : SlugVersionRepository,
  dependencyConfigRepository : DependencyConfigRepository
)(implicit ec: ExecutionContext) extends Logging {

  private def classpathOrderedDependencies(slugInfo: SlugInfo): List[SlugDependency] =
    slugInfo.classpath
      .split(":")
      .map(_.replace("$lib_dir/", s"${slugInfo.name}-${slugInfo.version}/lib/"))
      .toList
      .flatMap(path => slugInfo.dependencies.filter(_.path == path))

  def addSlugInfo(slugInfo: SlugInfo): Future[Unit] = {
    val slug = slugInfo.copy(dependencies = classpathOrderedDependencies(slugInfo))

    for {
      // Determine which slug is latest from the existing collection, not relying on the potentially stale state of the message
      _        <- slugInfoRepository.add(slug)
      isLatest <- slugVersionRepository.getMaxVersion(name = slug.name)
        .map {
          case None             => true
          case Some(maxVersion) => val isLatest = maxVersion == slug.version
            logger.info(s"Slug ${slug.name} ${slug.version} isLatest=$isLatest (latest is: $maxVersion)")
            isLatest
        }

      _        <- if (isLatest) slugInfoRepository.setFlag(SlugInfoFlag.Latest, slug.name, slug.version) else Future.unit
    } yield ()
  }

  def addDependencyConfigurations(dependencyConfigs: Seq[DependencyConfig]): Future[Unit] =
    dependencyConfigs.toList.traverse_(dependencyConfigRepository.add)

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugInfoRepository.getSlugInfo(name, flag)

  def findDependencyConfig(group: String, artefact: String, version: String): Future[Option[DependencyConfig]] =
    dependencyConfigRepository.getDependencyConfig(group, artefact, version)
}
