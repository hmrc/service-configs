/*
 * Copyright 2020 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, SlugDependency, SlugInfo, SlugInfoFlag}
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugConfigurationInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugConfigurationService @Inject()(
  slugConfigurationInfoRepository: SlugConfigurationInfoRepository,
  dependencyConfigRepository: DependencyConfigRepository,
  configService: ConfigService)(implicit executionContext: ExecutionContext) {

  private def classpathOrderedDependencies(slugInfo: SlugInfo): List[SlugDependency] =
    slugInfo.classpath
      .split(":")
      .map(_.replace("$lib_dir/", s"./${slugInfo.name}-${slugInfo.version}/lib/"))
      .toList
      .flatMap(path => slugInfo.dependencies.filter(_.path == path))

  def addSlugInfo(slugInfo: SlugInfo): Future[Unit] = {
    val slug = slugInfo.copy(dependencies = classpathOrderedDependencies(slugInfo))
    slugConfigurationInfoRepository.add(slug)
  }

  def addDependencyConfigurations(dependencyConfigs: Seq[DependencyConfig]): Future[Seq[Unit]] =
    Future.sequence(dependencyConfigs.map(dependencyConfigRepository.add))

  def getSlugInfos(name: String, version: Option[String]): Future[Seq[SlugInfo]] =
    slugConfigurationInfoRepository.getSlugInfos(name, version)

  def getSlugInfo(name: String, flag: SlugInfoFlag): Future[Option[SlugInfo]] =
    slugConfigurationInfoRepository.getSlugInfo(name, flag)

  def findDependencyConfig(group: String, artefact: String, version: String): Future[Option[DependencyConfig]] =
    dependencyConfigRepository.getDependencyConfig(group, artefact, version)

}
