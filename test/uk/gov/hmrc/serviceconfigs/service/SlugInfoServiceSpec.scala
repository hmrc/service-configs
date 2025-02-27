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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.{never, verify, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.ReleasesApiConnector.{Deployment, DeploymentConfigFile, ServiceDeploymentInformation}
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, ReleasesApiConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.parser.ConfigValue
import uk.gov.hmrc.serviceconfigs.persistence._
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigEnvironment, ConfigSourceEntries, RenderedConfigSourceValue}

import java.time.temporal.ChronoUnit
import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class SlugInfoServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with MockitoSugar
     with IntegrationPatience:

  private given HeaderCarrier = HeaderCarrier()

  "SlugInfoService.updateMetadata" should:
    "clear flags of decommissioned services" in new Setup:
      val decommissionedServices = List(
        TeamsAndRepositoriesConnector.DecommissionedRepo(RepoName("service1"))
      , TeamsAndRepositoriesConnector.DecommissionedRepo(RepoName("service2"))
      )

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedReleasesApiConnector.getWhatsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[DigitalService]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(Seq.empty))

      when(mockedTeamsAndReposConnector.getDecommissionedServices())
        .thenReturn(Future.successful(decommissionedServices))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(Seq.empty))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedDeploymentConfigRepository.delete(any[ServiceName], any[Environment], any[Boolean]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[SlugInfoFlag], any[List[ServiceName]]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      Environment.values.foreach: env =>
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), ServiceName("service1"))
        verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), ServiceName("service2"))
        verify(mockedDeployedConfigRepository).delete(ServiceName("service1"), env)
        verify(mockedDeployedConfigRepository).delete(ServiceName("service2"), env)
        verify(mockedAppliedConfigRepository).delete(ServiceName("service1"), env)
        verify(mockedAppliedConfigRepository).delete(ServiceName("service2"), env)

      verify(mockedSlugInfoRepository).clearFlags(SlugInfoFlag.Latest, decommissionedServices.map(x => ServiceName(x.repoName.asString)))

    "clear latest flag for services that have been deleted/archived" in new Setup:
      val knownServices  = List(ServiceName("service1"), ServiceName("service2"), ServiceName("service3"))
      val activeServices = List(Repo(RepoName("service1"), Seq.empty, None), Repo(RepoName("service3"), Seq.empty, None))
      val archived       = List(ServiceName("service2"))

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[DigitalService]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedTeamsAndReposConnector.getDecommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(knownServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[SlugInfoFlag], any[List[ServiceName]]))
        .thenReturn(Future.unit)

      when(mockedDeploymentConfigRepository.delete(any[ServiceName], any[Environment], any[Boolean]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      Environment.values.foreach: env =>
        archived.foreach: service =>
          verify(mockedSlugInfoRepository).clearFlag(SlugInfoFlag.ForEnvironment(env), service)
          verify(mockedDeployedConfigRepository).delete(service, env)
          verify(mockedAppliedConfigRepository).delete(service, env)

      verify(mockedSlugInfoRepository).clearFlags(SlugInfoFlag.Latest, archived)

    "detect any services that do not have a 'latest' flag and set based on maxVersion" in new Setup:
      val activeServices = List(Repo       (RepoName("service1"), Seq.empty, None), Repo(RepoName("service2"), Seq.empty, None), Repo(RepoName("service3"), Seq.empty, None))
      val knownServices  = List(ServiceName("service1"), ServiceName("service2"), ServiceName("service3"))
      val latestServices = List(ServiceName("service1")                         , ServiceName("service3"))
      val missingLatest  = ServiceName("service2")
      val maxVersion     = Version("1.0.0")

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedReleasesApiConnector.getWhatsRunningWhere())
        .thenReturn(Future.successful(List.empty))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[DigitalService]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedTeamsAndReposConnector.getDecommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(latestServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedDeploymentConfigRepository.delete(any[ServiceName], any[Environment], any[Boolean]))
        .thenReturn(Future.unit)


      when(mockedSlugInfoRepository.clearFlags(any[SlugInfoFlag], any[List[ServiceName]]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.getMaxVersion(any[ServiceName]))
        .thenReturn(Future.successful(Some(maxVersion)))

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[ServiceName], any[Version]))
        .thenReturn(Future.unit)

      service.updateMetadata().futureValue

      verify(mockedSlugInfoRepository).setFlag(SlugInfoFlag.Latest, missingLatest, maxVersion)

    "lookup and store config files for deployment" in new Setup:
      val serviceName1 = ServiceName("service1")
      val serviceName2 = ServiceName("service2")
      val knownServices  = List(serviceName1, serviceName2)
      val activeServices = knownServices.map(s => Repo(RepoName(s.asString), Seq.empty, None))
      val latestServices = List(serviceName1, serviceName2)

      when(mockedSlugInfoRepository.getUniqueSlugNames())
        .thenReturn(Future.successful(knownServices))

      when(mockedConfigService.configSourceEntries(any[ConfigEnvironment], any[ServiceName], any[Option[Version]], any[Boolean])(using any[HeaderCarrier]))
        .thenReturn(Future.successful((Map.empty, Option.empty[DeploymentConfig])))

      when(mockedReleasesApiConnector.getWhatsRunningWhere())
        .thenReturn(Future.successful(Seq(
          ServiceDeploymentInformation(serviceName1, Seq(
            Deployment(serviceName1, Environment.QA, Version("1.0.0"), deploymentId = "deploymentId1", config = Seq(
              DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                , commitId = CommitId("1")),
              DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")       , commitId = CommitId("2")),
              DeploymentConfigFile(repoName = RepoName("app-config-qa")        , fileName = FileName("service1.yaml")                , commitId = CommitId("3"))
            ), lastDeployed = now),
            Deployment(serviceName1, Environment.Production, Version("1.0.1"), deploymentId = "deploymentId2", config = Seq(
              DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                 , commitId = CommitId("4")),
              DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("production-microservice-common"), commitId = CommitId("5")),
              DeploymentConfigFile(repoName = RepoName("app-config-production"), fileName = FileName("service1.yaml")                 , commitId = CommitId("6"))
            ), lastDeployed = now)
          )),
          ServiceDeploymentInformation(serviceName2, Seq(
            Deployment(serviceName2, Environment.QA, Version("1.0.2"), deploymentId = "deploymentId3", config = Seq(
              DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service2.conf")                 , commitId = CommitId("7")),
              DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")        , commitId = CommitId("8")),
              DeploymentConfigFile(repoName = RepoName("app-config-production"), fileName = FileName("service2.yaml")                 , commitId = CommitId("9"))
            ), lastDeployed = now)
          ))
        )))

      when(mockedTeamsAndReposConnector.getRepos(eqTo(Some(false)), any[Option[String]], any[Option[TeamName]], any[Option[DigitalService]], any[Option[ServiceType]], any[List[Tag]]))
        .thenReturn(Future.successful(activeServices))

      when(mockedTeamsAndReposConnector.getDecommissionedServices())
        .thenReturn(Future.successful(List.empty))

      when(mockedSlugInfoRepository.getAllLatestSlugInfos())
        .thenReturn(Future.successful(latestServices.map(toSlugInfo)))

      when(mockedSlugInfoRepository.clearFlag(any[SlugInfoFlag], any[ServiceName]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedDeploymentEventRepository.put(any[DeploymentEventRepository.DeploymentEvent]))
        .thenReturn(Future.unit)

      when(mockedAppliedConfigRepository.delete(any[ServiceName], any[Environment]))
        .thenReturn(Future.unit)

      when(mockedDeploymentConfigRepository.delete(any[ServiceName], any[Environment], any[Boolean]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.clearFlags(any[SlugInfoFlag], any[List[ServiceName]]))
        .thenReturn(Future.unit)

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[ServiceName], any[Version]))
        .thenReturn(Future.unit)

      //Below three mocks to return existing deployedConfig, all have a lastUpdated time 5 mins prior to the processStart time (defined in setup)
      //And also have a different configId to the new config.
      //This is required in order to trigger `updateDeployedConfig`

      when(mockedDeploymentEventRepository.findDeploymentEvent(any[String]))  // Deployment does not currently exist
        .thenReturn(Future.successful(Option.empty[DeploymentEventRepository.DeploymentEvent]))

      when(mockedDeployedConfigRepository.find(serviceName = serviceName1, environment = Environment.QA))
        .thenReturn(Future.successful(
          Option(
            DeployedConfigRepository.DeployedConfig(
              serviceName     = serviceName1,
              environment     = Environment.QA,
              deploymentId    = "deploymentId1",
              configId        = "service1_0.0.9_app-config-base_1_app-config-common_2_app-config-qa_3",
              appConfigBase   = Some("content1"),
              appConfigCommon = Some("content2"),
              appConfigEnv    = Some("content3"),
              lastUpdated     = now.minus(5, ChronoUnit.MINUTES)
            )
          )
        ))

      when(mockedDeployedConfigRepository.find(serviceName = serviceName1, environment = Environment.Production))
        .thenReturn(Future.successful(
          Option(
            DeployedConfigRepository.DeployedConfig(
              serviceName     = serviceName1,
              environment     = Environment.Production,
              deploymentId    = "deploymentId2",
              configId        = "service1_0.0.9_app-config-base_4_app-config-common_5_app-config-production_6",
              appConfigBase   = Some("content4"),
              appConfigCommon = Some("content5"),
              appConfigEnv    = Some("content6"),
              lastUpdated     = now.minus(5, ChronoUnit.MINUTES)
            )
          )
        ))

      when(mockedDeployedConfigRepository.find(serviceName = serviceName2, environment = Environment.QA))
        .thenReturn(Future.successful(
          Option(
            DeployedConfigRepository.DeployedConfig(
              serviceName     = serviceName2,
              environment     = Environment.QA,
              deploymentId    = "deploymentId3",
              configId        = "service2_0.0.9_app-config-base_7_app-config-common_8_app-config-production_9",
              appConfigBase   = Some("content7"),
              appConfigCommon = Some("content8"),
              appConfigEnv    = Some("content9"),
              lastUpdated     = now.minus(5, ChronoUnit.MINUTES)
            )
          )
        ))

      when(mockedConfigConnector.appConfigBaseConf(any[ServiceName], any[CommitId])(using any[HeaderCarrier]))
        .thenAnswer(answer => Future.successful(Some(s"content${answer.getArgument[String](1)}")))

      when(mockedConfigConnector.appConfigCommonYaml(any[FileName], any[CommitId])(using any[HeaderCarrier]))
        .thenAnswer(answer => Future.successful(Some(s"content${answer.getArgument[String](1)}")))

      when(mockedConfigConnector.appConfigEnvYaml(any[Environment], any[ServiceName], any[CommitId])(using any[HeaderCarrier]))
        .thenAnswer(answer => Future.successful(Some(s"content${answer.getArgument[String](2)}")))

      when(mockedDeployedConfigRepository.put(any[DeployedConfigRepository.DeployedConfig]))
        .thenReturn(Future.unit)

      when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Option[Version]], any[Boolean])(using any[HeaderCarrier]))
        .thenAnswer(answer =>
          Future.successful(
            ( Seq(ConfigSourceEntries("s", Some("u"), Map(s"${answer.getArgument[ConfigService.ConfigEnvironment](0).name}.${answer.getArgument[String](1)}" -> ConfigValue("v"))))
            , Option.empty[DeploymentConfig]
            )
          )
        )

      when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
        .thenAnswer(answer =>
          answer.getArgument[Seq[ConfigSourceEntries]](0).headOption.toSeq.flatMap(cse => cse.entries.map { case (key, value) => key -> ConfigService.ConfigSourceValue(cse.source, cse.sourceUrl, value) }).toMap
        )

      when(mockedConfigService.resultingConfig(any[Option[DeploymentConfig]]))
        .thenReturn(Map.empty[String, ConfigValue])

      when(mockedAppliedConfigRepository.put(any[ServiceName], any[Environment], any[Map[String, RenderedConfigSourceValue]]))
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
        appConfigEnv    = Some("content3"),
        lastUpdated     = now
      ))

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName1,
        environment     = Environment.Production,
        deploymentId    = "deploymentId2",
        configId        = "service1_1.0.1_app-config-base_4_app-config-common_5_app-config-production_6",
        appConfigBase   = Some("content4"),
        appConfigCommon = Some("content5"),
        appConfigEnv    = Some("content6"),
        lastUpdated     = now
      ))

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName2,
        environment     = Environment.QA,
        deploymentId    = "deploymentId3",
        configId        = "service2_1.0.2_app-config-base_7_app-config-common_8_app-config-production_9",
        appConfigBase   = Some("content7"),
        appConfigCommon = Some("content8"),
        appConfigEnv    = Some("content9"),
        lastUpdated     = now
      ))

      verify(mockedAppliedConfigRepository).put(serviceName1, Environment.QA        , Map("qa.service1"         -> RenderedConfigSourceValue("s", Some("u"), "v")))
      verify(mockedAppliedConfigRepository).put(serviceName1, Environment.Production, Map("production.service1" -> RenderedConfigSourceValue("s", Some("u"), "v")))
      verify(mockedAppliedConfigRepository).put(serviceName2, Environment.QA        , Map("qa.service2"         -> RenderedConfigSourceValue("s", Some("u"), "v")))

  "SlugInfoService.updateDeployment" should:
    """updateDeployedConfig when :
    |  1. The current deployedConfig configID differs from the latest Deployment ConfigID
    |  2. The current deployedConfig lastUpdated timestamp is prior to the latest Deployment timestamp.""" in new SetupUpdateDeployment:

      when(mockedDeployedConfigRepository.find(serviceName = serviceName1, environment = Environment.QA))
        .thenReturn(Future.successful(
          Some(
            DeployedConfigRepository.DeployedConfig(
              serviceName     = serviceName1,
              environment     = Environment.QA,
              deploymentId    = "deploymentId1",
              configId        = "service1_0.0.9_app-config-base_1_app-config-common_2_app-config-qa_3",
              appConfigBase   = Some("content1"),
              appConfigCommon = Some("content2"),
              appConfigEnv    = Some("content3"),
              lastUpdated     = now.minus(5, ChronoUnit.MINUTES)
            )
          )
        ))

      service.updateDeployment(env = Environment.QA, deployment = newDeployment, serviceName = serviceName1).futureValue

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName1,
        environment     = Environment.QA,
        deploymentId    = "deploymentId1",
        configId        = "service1_1.0.0_app-config-base_1_app-config-common_2_app-config-qa_3",
        appConfigBase   = Some("content1"),
        appConfigCommon = Some("content2"),
        appConfigEnv    = Some("content3"),
        lastUpdated     = now
      ))

    "updateDeployedConfig when no deployedConfig exists for the given serviceName/environment" in new SetupUpdateDeployment:
      when(mockedDeployedConfigRepository.find(serviceName = serviceName1, environment = Environment.QA))
        .thenReturn(Future.successful(
          None
        ))

      service.updateDeployment(env = Environment.QA, deployment = newDeployment, serviceName = serviceName1).futureValue

      verify(mockedDeployedConfigRepository).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName1,
        environment     = Environment.QA,
        deploymentId    = "deploymentId1",
        configId        = "service1_1.0.0_app-config-base_1_app-config-common_2_app-config-qa_3",
        appConfigBase   = Some("content1"),
        appConfigCommon = Some("content2"),
        appConfigEnv    = Some("content3"),
        lastUpdated     = now
      ))

    """Not update deployedConfig when :
    |  1. The current deployedConfig lastUpdated timestamp is after the latest Deployment timestamp
    |  2. The config Ids differ""" in new Setup:
      val serviceName1 = ServiceName("service1")

      when(mockedConfigService.configSourceEntries(any[ConfigEnvironment], any[ServiceName], any[Option[Version]], any[Boolean])(using any[HeaderCarrier]))
        .thenReturn(Future.successful((Map.empty, Option.empty[DeploymentConfig])))

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[ServiceName], any[Version]))
        .thenReturn(Future.unit)

      when(mockedDeploymentEventRepository.put(any[DeploymentEventRepository.DeploymentEvent]))
        .thenReturn(Future.unit)

      when(mockedDeploymentEventRepository.findDeploymentEvent(any[String]))  // Deployment does not exist
        .thenReturn(Future.successful(Option.empty[DeploymentEventRepository.DeploymentEvent]))

      when(mockedDeployedConfigRepository.find(serviceName = serviceName1, environment = Environment.QA))
        .thenReturn(Future.successful(
          Option(
            DeployedConfigRepository.DeployedConfig(
              serviceName     = serviceName1,
              environment     = Environment.QA,
              deploymentId    = "deploymentId1",
              configId        = "service1_0.0.9_app-config-base_1_app-config-common_2_app-config-qa_3",
              appConfigBase   = Some("content1"),
              appConfigCommon = Some("content2"),
              appConfigEnv    = Some("content3"),
              lastUpdated     = now.plus(5, ChronoUnit.MINUTES)
            )
          )
        ))

      val newDeployment = Deployment(serviceName1, Environment.QA, Version("1.0.0"), deploymentId = "deploymentId1", config = Seq(
        DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                , commitId = CommitId("1")),
        DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")       , commitId = CommitId("2")),
        DeploymentConfigFile(repoName = RepoName("app-config-qa")        , fileName = FileName("service1.yaml")                , commitId = CommitId("3"))
      ), lastDeployed = now)

      service.updateDeployment(env = Environment.QA, deployment = newDeployment, serviceName = serviceName1).futureValue

      verify(mockedDeployedConfigRepository, never).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName1,
        environment     = Environment.QA,
        deploymentId    = "deploymentId1",
        configId        = "service1_1.0.0_app-config-base_1_app-config-common_2_app-config-qa_3",
        appConfigBase   = Some("content1"),
        appConfigCommon = Some("content2"),
        appConfigEnv    = Some("content3"),
        lastUpdated     = now
      ))

    """Not update deployedConfig when:
    |  1. the latest Deployment configId is the same as that of the current deployedConfig,
    |  2. the current deployedConfig lastUpdated timestamp is BEFORE the latest Deployment dataTimestamp""".stripMargin in new Setup:
      val serviceName1 = ServiceName("service1")

      when(mockedConfigService.configSourceEntries(any[ConfigEnvironment], any[ServiceName], any[Option[Version]], any[Boolean])(using any[HeaderCarrier]))
        .thenReturn(Future.successful((Map.empty, Option.empty[DeploymentConfig])))

      when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[ServiceName], any[Version]))
        .thenReturn(Future.unit)

      when(mockedDeploymentEventRepository.findDeploymentEvent(any[String]))  // Deployment does not exist
        .thenReturn(Future.successful(Option.empty[DeploymentEventRepository.DeploymentEvent]))

      when(mockedDeploymentEventRepository.put(any[DeploymentEventRepository.DeploymentEvent]))
        .thenReturn(Future.unit)

      when(mockedDeploymentEventRepository.put(any[DeploymentEventRepository.DeploymentEvent]))
        .thenReturn(Future.unit)

      when(mockedDeployedConfigRepository.find(serviceName = serviceName1, environment = Environment.QA))
        .thenReturn(Future.successful(
          Option(
            DeployedConfigRepository.DeployedConfig(
              serviceName     = serviceName1,
              environment     = Environment.QA,
              deploymentId    = "deploymentId1",
              configId        = "service1_1.0.0_app-config-base_1_app-config-common_2_app-config-qa_3",
              appConfigBase   = Some("content1"),
              appConfigCommon = Some("content2"),
              appConfigEnv    = Some("content3"),
              lastUpdated     = now.minus(5, ChronoUnit.MINUTES)
            )
          )
        ))

      val newDeployment = Deployment(serviceName1, Environment.QA, Version("1.0.0"), deploymentId = "deploymentId1", config = Seq(
        DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                , commitId = CommitId("1")),
        DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")       , commitId = CommitId("2")),
        DeploymentConfigFile(repoName = RepoName("app-config-qa")        , fileName = FileName("service1.yaml")                , commitId = CommitId("3"))
      ), lastDeployed = now)

      service.updateDeployment(env = Environment.QA, deployment = newDeployment, serviceName = serviceName1).futureValue

      verify(mockedDeployedConfigRepository, never).put(DeployedConfigRepository.DeployedConfig(
        serviceName     = serviceName1,
        environment     = Environment.QA,
        deploymentId    = "deploymentId1",
        configId        = "service1_1.0.0_app-config-base_1_app-config-common_2_app-config-qa_3",
        appConfigBase   = Some("content1"),
        appConfigCommon = Some("content2"),
        appConfigEnv    = Some("content3"),
        lastUpdated     = now
      ))


    "Not update when the deployment has already been added" in new Setup:
      val serviceName1 = ServiceName("service1")

      when(mockedDeploymentEventRepository.findDeploymentEvent(any[String]))  // Deployment already exists
        .thenReturn(Future.successful(Some(DeploymentEventRepository.DeploymentEvent(
          serviceName            = serviceName1
        , environment            = Environment.QA
        , version                = Version("1.0.0")
        , deploymentId           = "deploymentId1"
        , configChanged          = Some(true)
        , deploymentConfigChanged= Some(true)
        , configId               = Some("configId1")
        , time                   = now
        ))))

      val newDeployment = Deployment(serviceName1, Environment.QA, Version("1.0.0"), deploymentId = "deploymentId1", config = Seq(
        DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                , commitId = CommitId("1")),
        DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")       , commitId = CommitId("2")),
        DeploymentConfigFile(repoName = RepoName("app-config-qa")        , fileName = FileName("service1.yaml")                , commitId = CommitId("3"))
      ), lastDeployed = now)

      service.updateDeployment(env = Environment.QA, deployment = newDeployment, serviceName = serviceName1)
        .futureValue should be (false)


  trait Setup:
    val mockedSlugInfoRepository         = mock[SlugInfoRepository]
    val mockedAppliedConfigRepository    = mock[AppliedConfigRepository]
    val mockedDeployedConfigRepository   = mock[DeployedConfigRepository]
    val mockedDeploymentConfigRepository = mock[DeploymentConfigRepository]
    val mockedDeploymentEventRepository = mock[DeploymentEventRepository]
    val mockedReleasesApiConnector       = mock[ReleasesApiConnector]
    val mockedTeamsAndReposConnector     = mock[TeamsAndRepositoriesConnector]
    val mockedConfigConnector            = mock[ConfigConnector]
    val mockedConfigService              = mock[ConfigService]

    val now = Instant.now()

    val service =
      SlugInfoService(
        mockedSlugInfoRepository
      , mockedAppliedConfigRepository
      , mockedDeployedConfigRepository
      , mockedDeploymentConfigRepository
      , mockedDeploymentEventRepository
      , mockedReleasesApiConnector
      , mockedTeamsAndReposConnector
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

  trait SetupUpdateDeployment extends Setup:
    val serviceName1 = ServiceName("service1")

    when(mockedDeploymentEventRepository.findDeploymentEvent(any[String]))
      .thenReturn(Future.successful(Option.empty[DeploymentEventRepository.DeploymentEvent]))

    when(mockedSlugInfoRepository.setFlag(any[SlugInfoFlag], any[ServiceName], any[Version]))
      .thenReturn(Future.unit)

    when(mockedConfigConnector.appConfigBaseConf(any[ServiceName], any[CommitId])(using any[HeaderCarrier]))
      .thenAnswer(answer => Future.successful(Some(s"content${answer.getArgument[String](1)}")))

    when(mockedConfigConnector.appConfigCommonYaml(any[FileName], any[CommitId])(using any[HeaderCarrier]))
      .thenAnswer(answer => Future.successful(Some(s"content${answer.getArgument[String](1)}")))

    when(mockedConfigConnector.appConfigEnvYaml(any[Environment], any[ServiceName], any[CommitId])(using any[HeaderCarrier]))
      .thenAnswer(answer => Future.successful(Some(s"content${answer.getArgument[String](2)}")))

    when(mockedDeployedConfigRepository.put(any[DeployedConfigRepository.DeployedConfig]))
      .thenReturn(Future.unit)

    when(mockedDeploymentEventRepository.put(any[DeploymentEventRepository.DeploymentEvent]))
      .thenReturn(Future.unit)

    when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Option[Version]], any[Boolean])(using any[HeaderCarrier]))
      .thenAnswer(answer =>
        Future.successful(
          ( Seq(ConfigSourceEntries("s", Some("u"), Map(s"${answer.getArgument[ConfigService.ConfigEnvironment](0).name}.${answer.getArgument[String](1)}" -> ConfigValue("v"))))
          , Option.empty[DeploymentConfig]
          )
        )
      )

    when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
      .thenAnswer(answer =>
        answer.getArgument[Seq[ConfigSourceEntries]](0).headOption.toSeq.flatMap(cse => cse.entries.map:
          case (key, value) => key -> ConfigService.ConfigSourceValue(cse.source, cse.sourceUrl, value)
        ).toMap
      )

    when(mockedConfigService.resultingConfig(any[Option[DeploymentConfig]]))
      .thenReturn(Map.empty[String, ConfigValue])

    when(mockedAppliedConfigRepository.put(any[ServiceName], any[Environment], any[Map[String, RenderedConfigSourceValue]]))
      .thenReturn(Future.unit)

    val newDeployment = Deployment(serviceName1, Environment.QA, Version("1.0.0"), deploymentId = "deploymentId1", config = Seq(
      DeploymentConfigFile(repoName = RepoName("app-config-base")      , fileName = FileName("service1.conf")                , commitId = CommitId("1")),
      DeploymentConfigFile(repoName = RepoName("app-config-common")    , fileName = FileName("qa-microservice-common")       , commitId = CommitId("2")),
      DeploymentConfigFile(repoName = RepoName("app-config-qa")        , fileName = FileName("service1.yaml")                , commitId = CommitId("3"))
    ), lastDeployed = now)
