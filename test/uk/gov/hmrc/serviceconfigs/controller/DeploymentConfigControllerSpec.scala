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

import org.mockito.ArgumentMatchers.{any, eq => eqTo}
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.MockitoSugar.{mock, reset, verify, when}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers._
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfigGrouped, ServiceName, TeamName}
import uk.gov.hmrc.serviceconfigs.service.DeploymentConfigService

import scala.concurrent.Future

class DeploymentConfigControllerSpec extends AnyWordSpec with Matchers with GuiceOneAppPerSuite with WireMockSupport with OptionValues {

  private val mockTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]
  private val mockDeploymentConfigService       = mock[DeploymentConfigService]

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .overrides(
        bind[TeamsAndRepositoriesConnector].to(mockTeamsAndRepositoriesConnector),
        bind[DeploymentConfigService].to(mockDeploymentConfigService)
      )
      .build()

  override def beforeEach(): Unit = {
    super.beforeEach()
    reset(mockTeamsAndRepositoriesConnector)
    reset(mockDeploymentConfigService)
  }

  "deploymentConfigGrouped" should {

    "return configs and get repositories when team name is a defined parameters" in {

      val teamName = TeamName("test")
      val expectedResult = DeploymentConfigGrouped(ServiceName("test"), Seq.empty)

      when(mockTeamsAndRepositoriesConnector.getRepos(any(), any(), any(), any(), any())).thenReturn(
        Future.successful(Seq(Repo("test")))
      )

      when(mockDeploymentConfigService.findGrouped(any(), any(), any())).thenReturn(
        Future.successful(Seq(expectedResult))
      )

      val request = FakeRequest(GET, routes.DeploymentConfigController.deploymentConfigGrouped(None, Some(teamName
      ), None, None).url)

      val result = route(app, request).value

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.toJson(Seq(expectedResult))

      verify(mockTeamsAndRepositoriesConnector).getRepos(
        archived = eqTo(None),
        repoType = eqTo(None),
        teamName = eqTo(Some(teamName)),
        serviceType = eqTo(None),
        tags = eqTo(Nil)
      )
    }

    "return configs and not get repositories when team name is not defined" in {

      val expectedResult = DeploymentConfigGrouped(ServiceName("test"), Seq.empty)

      when(mockDeploymentConfigService.findGrouped(any(), any(), any())).thenReturn(
        Future.successful(Seq(expectedResult))
      )

      val request = FakeRequest(GET, routes.DeploymentConfigController.deploymentConfigGrouped(None, None, None, None).url)

      val result = route(app, request).value

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.toJson(Seq(expectedResult))

      verifyNoInteractions(mockTeamsAndRepositoriesConnector)
    }

  }

}
