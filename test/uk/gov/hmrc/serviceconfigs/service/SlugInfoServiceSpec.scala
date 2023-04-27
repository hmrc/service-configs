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

import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.connector.{GithubRawConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{SlugInfo, SlugInfoFlag, Version}
import uk.gov.hmrc.serviceconfigs.persistence.{SlugInfoRepository, SlugVersionRepository}

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugInfoServiceSpec
  extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with MockitoSugar
    with IntegrationPatience {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "SlugInfoService.updateMetadata" should {
    "clear flags of decommissioned services" in {
      val boot = Boot.init

      val decommissionedServices = List("service1")

      when(boot.mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(Seq.empty))

      when(boot.mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(boot.mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]]))
        .thenReturn(Future.successful(Seq.empty))

      when(boot.mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(decommissionedServices))

      when(boot.mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(Seq.empty))

      when(boot.mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      boot.service.updateMetadata().futureValue

      verify(boot.mockedSlugInfoRepository).clearFlags(SlugInfoFlag.values, decommissionedServices)
    }

    "clear latest flag for services that have been deleted/archived" in {
      val boot = Boot.init

      val knownServices  = List("service1", "service2", "service3")
      val activeServices = List("service1", "service3").map(Repo.apply)
      val archived       = List("service2")

      when(boot.mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(boot.mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(boot.mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]]))
        .thenReturn(Future.successful(activeServices))

      when(boot.mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(boot.mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(knownServices.map(Boot.toSlugInfo)))

      when(boot.mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.unit)

      when(boot.mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      boot.service.updateMetadata().futureValue

      verify(boot.mockedSlugInfoRepository).clearFlags(List(SlugInfoFlag.Latest), archived)
    }

    "detect any services that do not have a 'latest' flag and set based on maxVersion" in {
      val boot = Boot.init

      val knownServices  = List("service1", "service2", "service3")
      val activeServices = List("service1", "service2", "service3").map(Repo.apply)
      val latestServices = List("service1", "service3")
      val missingLatest  = "service2"
      val maxVersion     = Version("1.0.0")

      when(boot.mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(boot.mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(boot.mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]]))
        .thenReturn(Future.successful(activeServices))

      when(boot.mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(boot.mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(latestServices.map(Boot.toSlugInfo)))

      when(boot.mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.unit)

      when(boot.mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      when(boot.mockedSlugVersionRepository.getMaxVersion(any[String]))
        .thenReturn(Future.successful(Some(maxVersion)))

      when(boot.mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[String], any[Version]))
        .thenReturn(Future.unit)

      boot.service.updateMetadata().futureValue

      verify(boot.mockedSlugInfoRepository).setFlag(SlugInfoFlag.Latest, missingLatest, maxVersion)
    }
  }

  case class Boot(
    mockedSlugInfoRepository     : SlugInfoRepository
  , mockedSlugVersionRepository  : SlugVersionRepository
  , mockedReleasesApiConnector   : ReleasesApiConnector
  , mockedTeamsAndReposConnector : TeamsAndRepositoriesConnector
  , mockedGithubRawConnector     : GithubRawConnector
  , service                      : SlugInfoService
  )

  object Boot {
    def init: Boot = {
      val mockedSlugInfoRepository     = mock[SlugInfoRepository]
      val mockedSlugVersionRepository  = mock[SlugVersionRepository]
      val mockedReleasesApiConnector   = mock[ReleasesApiConnector]
      val mockedTeamsAndReposConnector = mock[TeamsAndRepositoriesConnector]
      val mockedGithubRawConnector     = mock[GithubRawConnector]

      val service = new SlugInfoService(
                          mockedSlugInfoRepository
                        , mockedSlugVersionRepository
                        , mockedReleasesApiConnector
                        , mockedTeamsAndReposConnector
                        , mockedGithubRawConnector
                        )

      Boot(
        mockedSlugInfoRepository
      , mockedSlugVersionRepository
      , mockedReleasesApiConnector
      , mockedTeamsAndReposConnector
      , mockedGithubRawConnector
      , service
      )
    }

    def toSlugInfo(name: String): SlugInfo =
      SlugInfo(
        uri = ""
        , created           = Instant.now
        , name              = name
        , version           = Version("0.0.0")
        , classpath         = ""
        , dependencies      = List.empty
        , applicationConfig = ""
        , includedAppConfig = Map.empty
        , loggerConfig      = ""
        , slugConfig        = ""
      )
  }

}
