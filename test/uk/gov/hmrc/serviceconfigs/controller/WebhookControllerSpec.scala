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

package uk.gov.hmrc.serviceconfigs.controller

import akka.actor.ActorSystem
import org.mockito.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.config.NginxConfig
import uk.gov.hmrc.serviceconfigs.service._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.Result
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class WebhookControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  "processGithubWebhook" should {
    "accept unknown repository" in new Setup {
      val response = postWebhook(repository = "123", branch = "yyy")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")
    }

    "update app-config-base" in new Setup {
      when(mockAppConfigService.updateAppConfigBase()).thenReturn(Future.unit)

      val response = postWebhook(repository = "app-config-base", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockAppConfigService, times(1)).updateAppConfigBase()
    }

    "update app-config-env" in new Setup {
      when(mockDeploymentConfigService.update(Environment.Production)).thenReturn(Future.unit)
      when(mockAppConfigService.updateAppConfigEnv(Environment.Production)).thenReturn(Future.unit)

      val response = postWebhook(repository = "app-config-production", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockDeploymentConfigService, times(1)).update(Environment.Production)
      verify(mockAppConfigService, times(1)).updateAppConfigEnv(Environment.Production)
    }

    "update app-config-common" in new Setup {
      when(mockAppConfigService.updateAppConfigCommon()).thenReturn(Future.unit)

      val response = postWebhook(repository = "app-config-common", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockAppConfigService, times(1)).updateAppConfigCommon()
    }

    "update bobby-config" in new Setup {
      when(mockBobbyRulesService.update()).thenReturn(Future.unit)

      val response = postWebhook(repository = "bobby-config", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockBobbyRulesService, times(1)).update()
    }

    "update build-jobs" in new Setup {
      when(mockBuildJobService.updateBuildJobs()).thenReturn(Future.unit)

      val response = postWebhook(repository = "build-jobs", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockBuildJobService, times(1)).updateBuildJobs()
    }

    "update grafana-dashboards" in new Setup {
      when(mockDashboardService.updateGrafanaDashboards()).thenReturn(Future.unit)

      val response = postWebhook(repository = "grafana-dashboards", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockDashboardService, times(1)).updateGrafanaDashboards()
    }

    "update kibana-dashboards" in new Setup {
      when(mockDashboardService.updateKibanaDashboards()).thenReturn(Future.unit)

      val response = postWebhook(repository = "kibana-dashboards", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockDashboardService, times(1)).updateKibanaDashboards()
    }

    "update admin-frontend-proxy" in new Setup {
      when(mockRoutesConfigService.updateAdminFrontendRoutes()).thenReturn(Future.unit)

      val response = postWebhook(repository = "admin-frontend-proxy", branch = "main")

      status(response) shouldBe 202
      contentAsJson(response) shouldBe Json.parse(s"""{"details":"Push accepted"}""")

      verify(mockRoutesConfigService, times(1)).updateAdminFrontendRoutes()
    }
  }

  trait Setup {

    implicit val as = ActorSystem()

    val mockConfiguration           = mock[Configuration          ]
    val mockNginxConfig             = mock[NginxConfig            ]
    val mockAlertConfigService      = mock[AlertConfigService     ]
    val mockAppConfigService        = mock[AppConfigService       ]
    val mockBobbyRulesService       = mock[BobbyRulesService      ]
    val mockDeploymentConfigService = mock[DeploymentConfigService]
    val mockNginxService            = mock[NginxService           ]
    val mockRoutesConfigService     = mock[RoutesConfigService    ]
    val mockBuildJobService         = mock[BuildJobService        ]
    val mockDashboardService        = mock[DashboardService       ]
    val mockOutagePageService       = mock[OutagePageService      ]

    val controller = new WebhookController(
      config                  = mockConfiguration,
      nginxConfig             = mockNginxConfig,
      alertConfigService      = mockAlertConfigService,
      appConfigService        = mockAppConfigService,
      bobbyRulesService       = mockBobbyRulesService,
      deploymentConfigService = mockDeploymentConfigService,
      nginxService            = mockNginxService,
      routesConfigService     = mockRoutesConfigService,
      buildJobService         = mockBuildJobService,
      dashboardService        = mockDashboardService,
      outagePageService       = mockOutagePageService,
      cc                      = stubControllerComponents()
    )

    def postWebhook(repository: String, branch: String): Future[Result] =
      call(
        controller.processGithubWebhook,
        FakeRequest(POST, "/webhook")
          .withHeaders("Content-Type" -> "application/json")
          .withBody(
            s"""{
              "repository": {
                "name": "$repository"
              },
              "ref": "refs/heads/$branch"
            }""")
          )

  }
}
