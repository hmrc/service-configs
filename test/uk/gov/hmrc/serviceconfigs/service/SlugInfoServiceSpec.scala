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
import uk.gov.hmrc.serviceconfigs.model.{CommitId, Environment, FileName, RepoName, ServiceName, ServiceType, SlugInfo, SlugInfoFlag, TeamName, Tag, Version}
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
      val decommissionedServices = List(ServiceName("service1"), ServiceName("service2"))

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(decommissionedServices))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[ServiceName]]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      Environment.values.foreach { env =>
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), ServiceName("service1"))
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), ServiceName("service2"))
        verify(mockedDeployedConfigRepository).delete(ServiceName("service1"), env)
        verify(mockedDeployedConfigRepository).delete(ServiceName("service2"), env)
        verify(mockedAppliedConfigRepository).delete(env, ServiceName("service1"))
        verify(mockedAppliedConfigRepository).delete(env, ServiceName("service2"))
      }
      verify(mockedSlugInfoRepository).clearFlags(List(SlugInfoFlag.Latest), decommissionedServices)
    }

    "clear latest flag for services that have been deleted/archived" in new Setup {
      val knownServices  = List(ServiceName("service1"), ServiceName("service2"), ServiceName("service3"))
      val activeServices = List(Repo("service1"), Repo("service3"))
      val archived       = List(ServiceName("service2"))

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(knownServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[ServiceName]]))
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
      val knownServices  = List(ServiceName("service1"), ServiceName("service2"), ServiceName("service3"))
      val activeServices = List(Repo       ("service1"), Repo       ("service2"), Repo       ("service3"))
      val latestServices = List(ServiceName("service1")                         , ServiceName("service3"))
      val missingLatest  = ServiceName("service2")
      val maxVersion     = Version("1.0.0")

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(latestServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[ServiceName]]))
        .thenReturn(Future.unit)

      when(mockedSlugVersionRepository.getMaxVersion(any[ServiceName]))
        .thenReturn(Future.successful(Some(maxVersion)))

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[ServiceName], any[Version]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.Latest, missingLatest, maxVersion)
    }

    "lookup and store config files for deployment" in new Setup {
      val serviceName1 = ServiceName("service1")
      val serviceName2 = ServiceName("service2")
      val knownServices  = List(serviceName1, serviceName2)
      val activeServices = knownServices.map(s => Repo(s.asString))
      val latestServices = List(serviceName1, serviceName2)

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatIsRunningWhere())
        .thenReturn(Future.successful(Seq(
          ServiceDeploymentInformation(serviceName1, Seq(
            Deployment(serviceName1, Some(Environment.QA), Version("1.0.0"), deploymentId = Some("deploymentId1"), config = Seq(
              DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                , commitId = CommitId("1")),
              DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")       , commitId = CommitId("2")),
              DeploymentConfigFile(repoName = RepoName("app-config-qa")        , fileName = FileName("service1.yaml")                , commitId = CommitId("3"))
            )),
            Deployment(serviceName1, Some(Environment.Production), Version("1.0.1"), deploymentId = Some("deploymentId2"), config = Seq(
              DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                 , commitId = CommitId("4")),
              DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("production-microservice-common"), commitId = CommitId("5")),
              DeploymentConfigFile(repoName = RepoName("app-config-production"), fileName = FileName("service1.yaml")                 , commitId = CommitId("6"))
            ))
          )),
          ServiceDeploymentInformation(serviceName2, Seq(
            Deployment(serviceName2, Some(Environment.QA), Version("1.0.2"), deploymentId = Some("deploymentId3"), config = Seq(
              DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service2.conf")                 , commitId = CommitId("7")),
              DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")        , commitId = CommitId("8")),
              DeploymentConfigFile(repoName = RepoName("app-config-production"), fileName = FileName("service2.yaml")                 , commitId = CommitId("9"))
            ))
          ))
        )))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedGithubRawConnector.decommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(latestServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[Environment], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[List[SlugInfoFlag]], any[List[ServiceName]]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[ServiceName], any[Version]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.hasProcessed(any[String]))
        .thenReturn(Future.successful(false))

      when(mockedConfigConnector.appConfigBaseConf(any[ServiceName], any[CommitId])(any[HeaderCarrier]))
        .thenAnswer((serviceName: ServiceName, commitId: CommitId) => Future.successful(Some(s"content${commitId.asString}")))

      when(mockedConfigConnector.appConfigCommonYaml(any[FileName], any[CommitId])(any[HeaderCarrier]))
        .thenAnswer((fileName: FileName, commitId: CommitId) => Future.successful(Some(s"content${commitId.asString}")))

      when(mockedConfigConnector.appConfigEnvYaml(any[Environment], any[ServiceName], any[CommitId])(any[HeaderCarrier]))
        .thenAnswer((environment: Environment, serviceName: ServiceName, commitId: CommitId) => Future.successful(Some(s"content${commitId.asString}")))

      when(mockedDeployedConfigRepository.put(any[DeployedConfigRepository.DeployedConfig]))
        .thenReturn(Future.unit)

      when(mockedConfigService.resultingConfig(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
        .thenAnswer((configEnvironment: ConfigService.ConfigEnvironment, serviceName: ServiceName, latest: Boolean) => Future.successful(Map(s"${configEnvironment.name}.${serviceName.asString}" -> "v")))

      when(mockedAppliedConfigRepository.put(any[Environment], any[ServiceName], any[Map[String, String]]))
        .thenReturn(Future.unit)


      service.updateMetadata().futureValue


      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.QA)        , serviceName1, Version("1.0.0"))
      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.Production), serviceName1, Version("1.0.1"))
      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.ForEnvironment(Environment.QA)        , serviceName2, Version("1.0.2"))

      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Staging   ), serviceName1)
      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Staging   ), serviceName2)
      verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(Environment.Production), serviceName2)

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName1,
        environment     = Environment.QA,
        deploymentId    = "deploymentId1",
        configId        = "service1_1.0.0_app-config-base_1_app-config-common_2_app-config-qa_3",
        appConfigBase   = Some("content1"),
        appConfigCommon = Some("content2"),
        appConfigEnv    = Some("content3")
      ))

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName1,
        environment     = Environment.Production,
        deploymentId    = "deploymentId2",
        configId        = "service1_1.0.1_app-config-base_4_app-config-common_5_app-config-production_6",
        appConfigBase   = Some("content4"),
        appConfigCommon = Some("content5"),
        appConfigEnv    = Some("content6")
      ))

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName2,
        environment     = Environment.QA,
        deploymentId    = "deploymentId3",
        configId        = "service2_1.0.2_app-config-base_7_app-config-common_8_app-config-production_9",
        appConfigBase   = Some("content7"),
        appConfigCommon = Some("content8"),
        appConfigEnv    = Some("content9")
      ))

      verify(mockedAppliedConfigRepository).put(Environment.QA        , serviceName1, Map("qa.service1"         -> "v"))
      verify(mockedAppliedConfigRepository).put(Environment.Production, serviceName1, Map("production.service1" -> "v"))
      verify(mockedAppliedConfigRepository).put(Environment.QA        , serviceName2, Map("qa.service2"         -> "v"))
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

    def toSlugInfo(name: ServiceName): SlugInfo =
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
