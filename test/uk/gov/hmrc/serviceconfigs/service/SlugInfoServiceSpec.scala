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
import uk.gov.hmrc.serviceconfigs.persistence.{AppliedConfigRepository, DeployedConfigRepository, SlugInfoRepository, SlugVersionRepository}
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

      when(mockedDeployedConfigRepository.delete(any[String], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      Environment.values.foreach { env =>
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), "service1")
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), "service2")
        verify(mockedDeployedConfigRepository).delete("service1", env)
        verify(mockedDeployedConfigRepository).delete("service2", env)
        verify(mockedAppliedConfigRepository).delete(env, "service1")
        verify(mockedAppliedConfigRepository).delete(env, "service2")
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

      when(mockedDeployedConfigRepository.delete(any[String], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      Environment.values.foreach { env =>
        archived.foreach { service =>
          verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), service)
          verify(mockedDeployedConfigRepository).delete(service, env)
          verify(mockedAppliedConfigRepository).delete(env, service)
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

      when(mockedDeployedConfigRepository.delete(any[String], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[String]))
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
            Deployment(Some(Environment.QA), Version("1.0.0"), deploymentId = "deploymentId1", config = Seq(
              DeploymentConfigFile(repoName = "app-config-base"      , fileName = "service1.conf"                , commitId = "1"),
              DeploymentConfigFile(repoName = "app-config-common"    , fileName = "qa-microservice-common"       , commitId = "2"),
              DeploymentConfigFile(repoName = "app-config-qa"        , fileName = "service1.yaml"                , commitId = "3")
            )),
            Deployment(Some(Environment.Production), Version("1.0.1"), deploymentId = "deploymentId2", config = Seq(
              DeploymentConfigFile(repoName = "app-config-base"      , fileName = "service1.conf"                 , commitId = "4"),
              DeploymentConfigFile(repoName = "app-config-common"    , fileName = "production-microservice-common", commitId = "5"),
              DeploymentConfigFile(repoName = "app-config-production", fileName = "service1.yaml"                 , commitId = "6")
            ))
          )),
          ServiceDeploymentInformation("service2", Seq(
            Deployment(Some(Environment.QA), Version("1.0.2"), deploymentId = "deploymentId3", config = Seq(
              DeploymentConfigFile(repoName = "app-config-base"      , fileName = "service2.conf"                 , commitId = "7"),
              DeploymentConfigFile(repoName = "app-config-common"    , fileName = "qa-microservice-common"        , commitId = "8"),
              DeploymentConfigFile(repoName = "app-config-production", fileName = "service2.yaml"                 , commitId = "9")
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

      when(mockedDeployedConfigRepository.delete(any[String], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[String]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[String]]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[String], any[Version]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.hasProcessed(any[String]))
        .thenReturn(Future.successful(false))

      when(mockedConfigConnector.appConfigBaseConf(any[String], any[String])(any[HeaderCarrier]))
        .thenAnswer((serviceName: String, commitId: String) => Future.successful(Some(s"content$commitId")))

      when(mockedConfigConnector.appConfigCommonYaml(any[Environment], any[String], any[String])(any[HeaderCarrier]))
        .thenAnswer((environment: Environment, fileName: String, commitId: String) => Future.successful(Some(s"content$commitId")))

      when(mockedConfigConnector.appConfigEnvYaml(any[Environment], any[String], any[String])(any[HeaderCarrier]))
        .thenAnswer((environment: Environment, serviceName: String, commitId: String) => Future.successful(Some(s"content$commitId")))

      when(mockedDeployedConfigRepository.put(any[DeployedConfigRepository.DeployedConfig]))
        .thenReturn(Future.unit)

      when(mockedConfigService.resultingConfig(any[ConfigService.ConfigEnvironment], any[String], any[Boolean])(any[HeaderCarrier]))
        .thenAnswer((configEnvironment: ConfigService.ConfigEnvironment, serviceName: String, latest: Boolean) => Future.successful(Map(s"${configEnvironment.name}.$serviceName" -> "v")))

      when(mockedAppliedConfigRepository.put(any[Environment], any[String], any[Map[String, String]]))
        .thenReturn(Future.unit)


      service.updateMetadata().futureValue


      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.QA)        , "service1", Version("1.0.0"))
      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.Production), "service1", Version("1.0.1"))
      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.QA)        , "service2", Version("1.0.2"))

      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Staging   ), "service1")
      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Staging   ), "service2")
      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Production), "service2")

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = "service1",
        environment     = Environment.QA,
        deploymentId    = "deploymentId1",
        appConfigBase   = Some("content1"),
        appConfigCommon = Some("content2"),
        appConfigEnv    = Some("content3")
      ))

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = "service1",
        environment     = Environment.Production,
        deploymentId    = "deploymentId2",
        appConfigBase   = Some("content4"),
        appConfigCommon = Some("content5"),
        appConfigEnv    = Some("content6")
      ))

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = "service2",
        environment     = Environment.QA,
        deploymentId    = "deploymentId3",
        appConfigBase   = Some("content7"),
        appConfigCommon = Some("content8"),
        appConfigEnv    = Some("content9")
      ))

      verify(mockedAppliedConfigRepository).put(Environment.QA        , "service1", Map("qa.service1"         -> "v"))
      verify(mockedAppliedConfigRepository).put(Environment.Production, "service1", Map("production.service1" -> "v"))
      verify(mockedAppliedConfigRepository).put(Environment.QA        , "service2", Map("qa.service2"         -> "v"))
    }
  }

  trait Setup {
    val mockedSlugInfoRepository       = mock[SlugInfoRepository]
    val mockedSlugVersionRepository    = mock[SlugVersionRepository]
    val mockedAppliedConfigRepository  = mock[AppliedConfigRepository]
    val mockedAppConfigService         = mock[AppConfigService]
    val mockedDeployedConfigRepository = mock[DeployedConfigRepository]
    val mockedReleasesApiConnector     = mock[ReleasesApiConnector]
    val mockedTeamsAndReposConnector   = mock[TeamsAndRepositoriesConnector]
    val mockedGithubRawConnector       = mock[GithubRawConnector]
    val mockedConfigConnector          = mock[ConfigConnector]
    val mockedConfigService            = mock[ConfigService]

    val service = new SlugInfoService(
                        mockedSlugInfoRepository
                      , mockedSlugVersionRepository
                      , mockedAppliedConfigRepository
                      , mockedAppConfigService
                      , mockedDeployedConfigRepository
                      , mockedReleasesApiConnector
                      , mockedTeamsAndReposConnector
                      , mockedGithubRawConnector
                      , mockedConfigConnector
                      , mockedConfigService
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
