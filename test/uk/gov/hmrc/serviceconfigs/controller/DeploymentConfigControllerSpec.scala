/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.controller

import org.apache.pekko.actor.ActorSystem
import org.mockito.scalatest.MockitoSugar
import org.mockito.Mockito.verifyNoInteractions
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ServiceName, TeamName}
import uk.gov.hmrc.serviceconfigs.persistence.DeploymentConfigRepository

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class DeploymentConfigControllerSpec
  extends AnyWordSpec
     with Matchers
     with WireMockSupport
     with OptionValues
     with MockitoSugar {

  "deploymentConfig" should {
    "return configs and get repositories when team name is a defined parameters" in new Setup {
      val teamName = TeamName("test")

      when(mockTeamsAndRepositoriesConnector.getRepos(any, any, any, any, any))
        .thenReturn(Future.successful(Seq(Repo("test"))))

      when(mockDeploymentConfigRepository.find(any, any, any))
        .thenReturn(Future.successful(Seq(
          DeploymentConfig(ServiceName("test"), None, Environment.Development, "zone", "depType", 5, 1, Map.empty, Map.empty))
        ))

      val result =
        call(
          controller.deploymentConfig(Seq(Environment.Development), None, Some(teamName)),
          FakeRequest(GET, "")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse("""[{
        "name"       : "test",
        "environment": "development",
        "zone"       : "zone",
        "type"       : "depType",
        "slots"      : 5,
        "instances"  : 1,
        "envVars"    : {},
        "jvm"        : {}
      }]""")

      verify(mockTeamsAndRepositoriesConnector).getRepos(
        archived    = None,
        repoType    = Some("Service"),
        teamName    = Some(teamName),
        serviceType = None,
        tags        = Nil
      )
    }

    "return configs and not get repositories when team name is not defined" in new Setup {
      when(mockDeploymentConfigRepository.find(any, any, any))
        .thenReturn(Future.successful(Seq(
          DeploymentConfig(ServiceName("test"), None, Environment.Development, "zone", "depType", 5, 1, Map.empty, Map.empty))
        ))

      val result =
        call(
          controller.deploymentConfig(Seq(Environment.Development), None, None),
          FakeRequest(GET, "")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse("""[{
        "name"       : "test",
        "environment": "development",
        "zone"       : "zone",
        "type"       : "depType",
        "slots"      : 5,
        "instances"  : 1,
        "envVars"    : {},
        "jvm"        : {}
      }]""")

      verifyNoInteractions(mockTeamsAndRepositoriesConnector)
    }
  }

  trait Setup {
    implicit val as: ActorSystem = ActorSystem()

    val mockTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
    val mockDeploymentConfigRepository    = mock[DeploymentConfigRepository]

    val controller = new DeploymentConfigController(
      deploymentConfigRepository    = mockDeploymentConfigRepository,
      teamsAndRepositoriesConnector = mockTeamsAndRepositoriesConnector,
      cc                            = stubControllerComponents()
    )
  }
}
