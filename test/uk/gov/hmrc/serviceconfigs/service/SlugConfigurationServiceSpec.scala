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

import java.time.Instant

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.connector.ReleasesApiConnector
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, SlugInfoRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugConfigurationServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with MockitoSugar:

  "SlugInfoService.addSlugInfo" should:
    "add slug with classpath ordered dependencies" in:
      val boot = Boot.init
      val slugName    = "my-slug"
      val slugVersion = "1.0.0"
      val sampleSlug = sampleSlugInfo(slugName, version = slugVersion, uri = "uri1")
      val classPathDependency    = SlugDependency(s"$slugName-$slugVersion/lib/org.scala-lang.scala-library-2.12.11.jar", "", "", "")
      val nonClassPathDependency = SlugDependency(s"$slugName-$slugVersion/lib/org.scala-lang.scala-library-2.12.12.jar", "", "", "")
      val slug = sampleSlug.copy(
        classpath    = "$lib_dir/../conf/:$lib_dir/uk.gov.hmrc.internal-auth-0.26.0-sans-externalized.jar:$lib_dir/org.scala-lang.scala-library-2.12.11.jar",
        dependencies = List(classPathDependency, nonClassPathDependency)
      )

      Mockito
        .when(boot.mockedReleasesApiConnector.getWhatsRunningWhere(any[ServiceName])(using any[HeaderCarrier]))
        .thenReturn(Future.successful(None))

      Mockito
        .when(boot.mockedSlugInfoRepository.add(any[SlugInfo], any[Set[Environment]]))
        .thenReturn(Future.unit)

      boot.service.addSlugInfo(slug).futureValue

      Mockito
        .verify(boot.mockedSlugInfoRepository)
        .add(slug.copy(dependencies = List(classPathDependency)), environments = Set.empty)

  def sampleSlugInfo(name: String, version: String, uri: String, latest: Boolean = true): SlugInfo =
    SlugInfo(
      created           = Instant.parse("2019-06-28T11:51:23.000Z"),
      uri               = uri,
      name              = ServiceName(name),
      version           = Version(version),
      classpath         = "",
      dependencies      = List.empty,
      applicationConfig = "",
      includedAppConfig = Map.empty,
      loggerConfig      = "",
      slugConfig        = ""
    )

  case class Boot(
    mockedReleasesApiConnector: ReleasesApiConnector
  , mockedSlugInfoRepository  : SlugInfoRepository
  , service                   : SlugConfigurationService
  )

  object Boot:
    def init: Boot =
      val mockedReleasesApiConnector       = mock[ReleasesApiConnector]
      val mockedSlugInfoRepository         = mock[SlugInfoRepository]
      val mockedDependencyConfigRepository = mock[DependencyConfigRepository]

      val service = SlugConfigurationService(
        mockedReleasesApiConnector
      , mockedSlugInfoRepository
      , mockedDependencyConfigRepository
      )

      Boot(
        mockedReleasesApiConnector
      , mockedSlugInfoRepository
      , service
      )
