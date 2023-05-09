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
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, GithubRawConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{Environment, SlugInfo, SlugInfoFlag, Version}
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
    "clear flags of decommissioned services" in new Setup {
      val decommissionedServices = List("service1")

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]]))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(decommissionedServices))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      verify(mockedSlugInfoRepository).clearFlags(SlugInfoFlag.values, decommissionedServices)
    }

    "clear latest flag for services that have been deleted/archived" in new Setup {
      val knownServices  = List("service1", "service2", "service3")
      val activeServices = List("service1", "service3").map(Repo.apply)
      val archived       = List("service2")

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(knownServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.serviceType(any[Environment], any[String]))
        .thenReturn(Future.successful(Some("microservice")))

      when(mockedAppConfigService.deleteAppConfigCommon(any[Environment], any[String], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.deleteAppConfigBase(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.deleteAppConfigEnv(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      verify(mockedSlugInfoRepository).clearFlags(List(SlugInfoFlag.Latest), archived)
    }

    "detect any services that do not have a 'latest' flag and set based on maxVersion" in new Setup {
      val knownServices  = List("service1", "service2", "service3")
      val activeServices = List("service1", "service2", "service3").map(Repo.apply)
      val latestServices = List("service1", "service3")
      val missingLatest  = "service2"
      val maxVersion     = Version("1.0.0")

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(latestServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.serviceType(any[Environment], any[String]))
        .thenReturn(Future.successful(Some("microservice")))

      when(mockedAppConfigService.deleteAppConfigCommon(any[Environment], any[String], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.deleteAppConfigBase(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.deleteAppConfigEnv(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      when(mockedSlugVersionRepository.getMaxVersion(any[String]))
        .thenReturn(Future.successful(Some(maxVersion)))

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[String], any[Version]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.Latest, missingLatest, maxVersion)
    }
  }

  trait Setup {
    val mockedSlugInfoRepository     = mock[SlugInfoRepository]
    val mockedSlugVersionRepository  = mock[SlugVersionRepository]
    val mockedAppConfigService       = mock[AppConfigService]
    val mockedReleasesApiConnector   = mock[ReleasesApiConnector]
    val mockedTeamsAndReposConnector = mock[TeamsAndRepositoriesConnector]
    val mockedGithubRawConnector     = mock[GithubRawConnector]
    val mockedConfigConnector        = mock[ConfigConnector]

    val service = new SlugInfoService(
                        mockedSlugInfoRepository
                      , mockedSlugVersionRepository
                      , mockedAppConfigService
                      , mockedReleasesApiConnector
                      , mockedTeamsAndReposConnector
                      , mockedGithubRawConnector
                      , mockedConfigConnector
                      )

    def toSlugInfo(name: String): SlugInfo =
      SlugInfo(
          uri               = ""
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
