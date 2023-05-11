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
import ReleasesApiConnector.{Deployment, DeploymentConfigFile, ServiceDeploymentInformation}

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
      val decommissionedServices = List("service1", "service2")

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

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.serviceType(any[Environment], eqTo("service1")))
        .thenReturn(Future.successful(Some("microservice")))

      when(mockedAppConfigService.serviceType(any[Environment], eqTo("service2")))
        .thenReturn(Future.successful(None)) // check we clean up what we can when we can't work out the service-type

      when(mockedAppConfigService.deleteAppConfigCommon(any[Environment], any[String], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.deleteAppConfigBase(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedAppConfigService.deleteAppConfigEnv(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      Environment.values.foreach { env =>
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), "service1")
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), "service2")
        verify(mockedAppConfigService).deleteAppConfigCommon(env, "service1", "microservice")
        verify(mockedAppConfigService, times(0)).deleteAppConfigCommon(eqTo(env), eqTo("service2"), any[String])
        verify(mockedAppConfigService).deleteAppConfigBase(env, "service1")
        verify(mockedAppConfigService).deleteAppConfigBase(env, "service2")
        verify(mockedAppConfigService).deleteAppConfigEnv(env, "service1")
        verify(mockedAppConfigService).deleteAppConfigEnv(env, "service2")
      }
      verify(mockedSlugInfoRepository).clearFlags(List(SlugInfoFlag.Latest), decommissionedServices)
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

      Environment.values.foreach { env =>
        archived.foreach { service =>
          verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), service)
          verify(mockedAppConfigService).deleteAppConfigCommon(env, service, "microservice")
          verify(mockedAppConfigService).deleteAppConfigBase(env, service)
          verify(mockedAppConfigService).deleteAppConfigEnv(env, service)
        }
      }
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

    "lookup and store config files for deployment" in new Setup {
      val knownServices  = List("service1", "service2")
      val activeServices = knownServices.map(Repo.apply)
      val latestServices = knownServices

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(Seq(
          ServiceDeploymentInformation("service1", Seq(
            Deployment(Some(Environment.QA), Version("1.0.0"), config = Seq(
              DeploymentConfigFile(repoName = "app-config-base"      , fileName = "service1.conf"                , commitId = "1"),
              DeploymentConfigFile(repoName = "app-config-qa"        , fileName = "service1.yaml"                , commitId = "2"),
              DeploymentConfigFile(repoName = "app-config-common"    , fileName = "qa-microservice-common"       , commitId = "3")
            )),
            Deployment(Some(Environment.Production), Version("1.0.1"), config = Seq(
              DeploymentConfigFile(repoName = "app-config-base"      , fileName = "service1.conf"                 , commitId = "4"),
              DeploymentConfigFile(repoName = "app-config-production", fileName = "service1.yaml"                 , commitId = "5"),
              DeploymentConfigFile(repoName = "app-config-common"    , fileName = "production-microservice-common", commitId = "6")
            ))
          )),
          ServiceDeploymentInformation("service2", Seq(
            Deployment(Some(Environment.QA), Version("1.0.2"), config = Seq(
              DeploymentConfigFile(repoName = "app-config-base"      , fileName = "service2.conf"                 , commitId = "7"),
              DeploymentConfigFile(repoName = "app-config-production", fileName = "service2.yaml"                 , commitId = "8"),
              DeploymentConfigFile(repoName = "app-config-common"    , fileName = "qa-microservice-common"        , commitId = "9")
            ))
          ))
        )))

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

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[String], any[Version]))
        .thenReturn(Future.unit)

      when(mockedConfigConnector.appConfigBaseConf(any[String], any[String])(any[HeaderCarrier]))
        .thenAnswer((serviceName: String, commitId: String) => Future.successful(Some(s"content$commitId")))

      when(mockedAppConfigService.putAppConfigBase(any[Environment], any[String], any[String], any[String]))
        .thenReturn(Future.unit)

      when(mockedConfigConnector.appConfigEnvYaml(any[Environment], any[String], any[String])(any[HeaderCarrier]))
        .thenAnswer((environment: Environment, serviceName: String, commitId: String) => Future.successful(Some(s"content$commitId")))

      when(mockedAppConfigService.putAppConfigEnv(any[Environment], any[String], any[String], any[String]))
        .thenReturn(Future.unit)

      when(mockedConfigConnector.appConfigCommonYaml(any[Environment], any[String], any[String])(any[HeaderCarrier]))
        .thenAnswer((environment: Environment, fileName: String, commitId: String) => Future.successful(Some(s"content$commitId")))

      when(mockedAppConfigService.putAppConfigCommon(any[String], any[String], any[String], any[String]))
        .thenReturn(Future.unit)


      service.updateMetadata().futureValue


      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.QA)        , "service1", Version("1.0.0"))
      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.Production), "service1", Version("1.0.1"))
      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.QA)        , "service2", Version("1.0.2"))

      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Staging   ), "service1")
      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Staging   ), "service2")
      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Production), "service2")

      verify(mockedAppConfigService).putAppConfigBase(Environment.QA        , "service1", "1", "content1")
      verify(mockedAppConfigService).putAppConfigBase(Environment.Production, "service1", "4", "content4")
      verify(mockedAppConfigService).putAppConfigBase(Environment.QA        , "service2", "7", "content7")

      verify(mockedAppConfigService).putAppConfigEnv(Environment.QA        , "service1", "2", "content2")
      verify(mockedAppConfigService).putAppConfigEnv(Environment.Production, "service1", "5", "content5")
      verify(mockedAppConfigService).putAppConfigEnv(Environment.QA        , "service2", "8", "content8")

      verify(mockedAppConfigService).putAppConfigCommon("service1", "qa-microservice-common"        , "3", "content3")
      verify(mockedAppConfigService).putAppConfigCommon("service1", "production-microservice-common", "6", "content6")
      verify(mockedAppConfigService).putAppConfigCommon("service2", "qa-microservice-common"        , "9", "content9")
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
