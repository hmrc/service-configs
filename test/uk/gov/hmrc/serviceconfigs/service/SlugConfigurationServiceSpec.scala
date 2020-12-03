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

import java.time.LocalDateTime

import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugConfigurationServiceSpec extends AnyWordSpec with Matchers
  with ScalaFutures with MockitoSugar {

  "SlugInfoService.addSlugInfo" should {
    "mark the slug as latest if it is the first slug with that name" in {
      val boot = Boot.init

      val slug = sampleSlugInfo(version = Version("1.0.0"), uri = "uri1")

      when(boot.mockedSlugInfoRepository.getSlugInfos(any, any)).thenReturn(Future.successful(List.empty))
      when(boot.mockedSlugInfoRepository.add(any)).thenReturn(Future.successful(()))
      when(boot.mockedSlugInfoRepository.setFlag(any, any, any)).thenReturn(Future.successful(()))

      boot.service.addSlugInfo(slug).futureValue

      verify(boot.mockedSlugInfoRepository, times(1)).setFlag(SlugInfoFlag.Latest, slug.name, slug.version)
      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }
    "mark the slug as latest if the version is latest" in {
      val boot = Boot.init

      val slugv1 = sampleSlugInfo(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = sampleSlugInfo(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugInfoRepository.getSlugInfos(slugv1.name, None)).thenReturn(Future.successful(List(slugv1, slugv2)))
      when(boot.mockedSlugInfoRepository.add(any)).thenReturn(Future.successful(()))
      when(boot.mockedSlugInfoRepository.setFlag(any, any, any)).thenReturn(Future.successful(()))

      boot.service.addSlugInfo(slugv2).futureValue
      verify(boot.mockedSlugInfoRepository, times(1)).setFlag(SlugInfoFlag.Latest, slugv2.name, Version("2.0.0"))

      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }
    "not use the latest flag from sqs/artefact processor" in {
      val boot = Boot.init

      val slugv1 = sampleSlugInfo(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = sampleSlugInfo(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugInfoRepository.getSlugInfos(slugv2.name, None)).thenReturn(Future.successful(List(slugv2)))
      when(boot.mockedSlugInfoRepository.add(any)).thenReturn(Future.successful(()))

      boot.service.addSlugInfo(slugv1).futureValue

      verify(boot.mockedSlugInfoRepository, times(1)).add(slugv1.copy(latest = false))
      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }
    "not mark the slug as latest if there is a later one already in the collection" in {
      val boot = Boot.init

      val slugv1 = sampleSlugInfo(version = Version("1.0.0"), uri = "uri1")
      val slugv2 = sampleSlugInfo(version = Version("2.0.0"), uri = "uri2")

      when(boot.mockedSlugInfoRepository.getSlugInfos(slugv2.name, None)).thenReturn(Future.successful(List(slugv2)))
      when(boot.mockedSlugInfoRepository.add(any)).thenReturn(Future.successful(()))

      boot.service.addSlugInfo(slugv1).futureValue

      verifyNoMoreInteractions(boot.mockedSlugInfoRepository)
    }

    "add slug with classpath ordered dependencies" in {
      val boot = Boot.init
      val sampleSlug = sampleSlugInfo(version = Version("1.0.0"), uri = "uri1")
      val classPathDependency = SlugDependency(s"${sampleSlug.name}-${sampleSlug.version}/lib/org.scala-lang.scala-library-2.12.11.jar", "", "", "")
      val nonClassPathDependency = SlugDependency(s"${sampleSlug.name}-${sampleSlug.version}/lib/org.scala-lang.scala-library-2.12.12.jar", "", "", "")
      val slug = sampleSlug.copy(
        classpath = "$lib_dir/../conf/:$lib_dir/uk.gov.hmrc.internal-auth-0.26.0-sans-externalized.jar:$lib_dir/org.scala-lang.scala-library-2.12.11.jar",
        dependencies = List(classPathDependency, nonClassPathDependency)
      )

      when(boot.mockedSlugInfoRepository.add(any)).thenReturn(Future.unit)
      when(boot.mockedSlugInfoRepository.getSlugInfos(any, any)).thenReturn(Future.successful(List.empty))
      when(boot.mockedSlugInfoRepository.setFlag(any, any, any)).thenReturn(Future.unit)

      boot.service.addSlugInfo(slug).futureValue

      verify(boot.mockedSlugInfoRepository).add(slug.copy(dependencies = List(classPathDependency), latest = false))
    }
  }

  def sampleSlugInfo(version: Version, uri: String, latest: Boolean = true): SlugInfo =
    SlugInfo(
      created = LocalDateTime.of(2019, 6, 28, 11, 51, 23),
      uri = uri,
      name = "my-slug",
      version = version,
      teams = List.empty,
      runnerVersion = "0.5.2",
      classpath = "",
      jdkVersion = "1.181.0",
      dependencies = List.empty,
      applicationConfig = "",
      slugConfig = "",
      latest = latest)

  case class Boot(
                   mockedSlugInfoRepository : SlugInfoRepository
                   , service                             : SlugConfigurationService
                 )

  object Boot {
    def init: Boot = {
      val mockedSlugInfoRepository              = mock[SlugInfoRepository]
      val mockedDependencyConfigRepository      = mock[DependencyConfigRepository]
      val mockedConfigService                   = mock[ConfigService]

      val service = new SlugConfigurationService(
        mockedSlugInfoRepository
        , mockedDependencyConfigRepository
        , mockedConfigService
      )

      Boot(
        mockedSlugInfoRepository
        , service
      )
    }
  }

}
