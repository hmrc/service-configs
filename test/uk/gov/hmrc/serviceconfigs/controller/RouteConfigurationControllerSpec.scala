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
import org.mockito.Mockito.when
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import play.api.libs.json.Json
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.serviceconfigs.model.*
import uk.gov.hmrc.serviceconfigs.model.Environment.Production
import uk.gov.hmrc.serviceconfigs.persistence.model.{MongoFrontendRoute, MongoShutterSwitch}
import uk.gov.hmrc.serviceconfigs.persistence.{AdminFrontendRouteRepository, FrontendRouteRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class RouteConfigurationControllerSpec
  extends AnyWordSpec
    with Matchers
    with WireMockSupport
    with OptionValues
    with MockitoSugar:

  "routes" should:
    "return all routing config for a service when environment and route type are not defined" in new Setup:
      val service1 = ServiceName("service1")

      when(mockAdminFrontendRouteRepository.findRoutes(Some(service1)))
        .thenReturn(Future.successful(Seq(
          AdminFrontendRoute(service1, "service1-admin-route", Map("production" -> List("mdtp"), "qa" -> List("mdtp")), "github-admin-link")
        )))

      when(mockFrontendRouteRepository.findRoutes(Some(service1), None, None))
        .thenReturn(Future.successful(Seq(
          MongoFrontendRoute(service1, "service1-frontend-route1", "path", Environment.Production, "", ruleConfigurationUrl = "github-frontend-link"               ),
          MongoFrontendRoute(service1, "service1-frontend-route1", "path", Environment.QA,         "", ruleConfigurationUrl = "github-frontend-link"               ),
          MongoFrontendRoute(service1, "service1-frontend-route2", "path", Environment.Production, "", ruleConfigurationUrl = "github-devhub-link", isDevhub = true),
        )))

      val result =
        call(
          controller.routes(Some(service1), None, None),
          FakeRequest(GET, "/routes/service1")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse("""[
        {
          "serviceName": "service1",
          "path": "service1-admin-route",
          "ruleConfigurationUrl": "github-admin-link",
          "isRegex": false,
          "routeType": "adminfrontend",
          "environment": "production"
        },
        {
          "serviceName": "service1",
          "path": "service1-admin-route",
          "ruleConfigurationUrl": "github-admin-link",
          "isRegex": false,
          "routeType": "adminfrontend",
          "environment": "qa"
        },
        {
          "serviceName": "service1",
          "path": "service1-frontend-route1",
          "ruleConfigurationUrl": "github-frontend-link",
          "isRegex": false,
          "routeType": "frontend",
          "environment": "production"
        },
        {
          "serviceName": "service1",
          "path": "service1-frontend-route1",
          "ruleConfigurationUrl": "github-frontend-link",
          "isRegex": false,
          "routeType": "frontend",
          "environment": "qa"
        },
        {
          "serviceName": "service1",
          "path": "service1-frontend-route2",
          "ruleConfigurationUrl": "github-devhub-link",
          "isRegex": false,
          "routeType": "devhub",
          "environment": "production"
        }
      ]""")

    "return all routing config for a service when environment is defined in parameters" in new Setup:
      val service1   = ServiceName("service1")
      val production = Environment.Production

      when(mockAdminFrontendRouteRepository.findRoutes(Some(service1)))
        .thenReturn(
          Future.successful(
            Seq(
              AdminFrontendRoute(service1, "service1-admin-route", Map("production" -> List("mdtp"), "qa" -> List("mdtp")), "github-admin-link"),
            )
          )
        )

      when(mockFrontendRouteRepository.findRoutes(Some(service1), Some(production), None))
        .thenReturn(
          Future.successful(
            Seq(
              MongoFrontendRoute(service1, "service1-frontend-route1", "path", Environment.Production, "", ruleConfigurationUrl = "github-frontend-link"),
              MongoFrontendRoute(service1, "service1-frontend-route2", "path", Environment.Production, "", ruleConfigurationUrl = "github-devhub-link", isDevhub = true),
            )
          )
        )

      val result =
        call(
          controller.routes(Some(service1), Some(production), None),
          FakeRequest(GET, "/routes/service1?environment=production")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse("""[
        {
          "serviceName": "service1",
          "path": "service1-admin-route",
          "ruleConfigurationUrl": "github-admin-link",
          "isRegex": false,
          "routeType": "adminfrontend",
          "environment": "production"
        },
        {
          "serviceName": "service1",
          "path": "service1-frontend-route1",
          "ruleConfigurationUrl": "github-frontend-link",
          "isRegex": false,
          "routeType": "frontend",
          "environment": "production"
        },
        {
          "serviceName": "service1",
          "path": "service1-frontend-route2",
          "ruleConfigurationUrl": "github-devhub-link",
          "isRegex": false,
          "routeType": "devhub",
          "environment": "production"
        }
      ]""")

    "return all routing config for a service when admin frontend route type is defined in parameters" in new Setup:
      val service1 = ServiceName("service1")
      val admin    = RouteType.AdminFrontend

      when(mockAdminFrontendRouteRepository.findRoutes(Some(service1)))
        .thenReturn(
          Future.successful(
            Seq(
              AdminFrontendRoute(service1, "service1-admin-route", Map("production" -> List("mdtp"), "qa" -> List("mdtp")), "github-admin-link"),
            )
          )
        )

      val result =
        call(
          controller.routes(Some(service1), None, Some(admin)),
          FakeRequest(GET, "/routes/service1?routeType=adminfrontend")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse(
        """[
        {
          "serviceName": "service1",
          "path": "service1-admin-route",
          "ruleConfigurationUrl": "github-admin-link",
          "isRegex": false,
          "routeType": "adminfrontend",
          "environment": "production"
        },
        {
          "serviceName": "service1",
          "path": "service1-admin-route",
          "ruleConfigurationUrl": "github-admin-link",
          "isRegex": false,
          "routeType": "adminfrontend",
          "environment": "qa"
        }
      ]"""
      )

    "return all routing config for a service when route type frontend is defined in parameters" in new Setup:
      val service1  = ServiceName("service1")
      val frontend  = RouteType.Frontend

      // when isDevhub equals false route type is a Frontend
      when(mockFrontendRouteRepository.findRoutes(Some(service1), None, isDevhub = Some(false)))
        .thenReturn(
          Future.successful(
            Seq(
              MongoFrontendRoute(service1, "service1-frontend-route1", "path", Environment.Production, "", ruleConfigurationUrl = "github-frontend-link")
            )
          )
        )

      val result =
        call(
          controller.routes(Some(service1), None, Some(frontend)),
          FakeRequest(GET, "/routes/service1?routeType=frontend")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse(
        """[
        {
          "serviceName": "service1",
          "path": "service1-frontend-route1",
          "ruleConfigurationUrl": "github-frontend-link",
          "isRegex": false,
          "routeType": "frontend",
          "environment": "production"
        }
      ]"""
      )

    "return all routing config for a service when route type devhub is defined in parameters" in new Setup:
      val service1 = ServiceName("service1")
      val devhub   = RouteType.Devhub

      // when isDevhub equals false route type is a Frontend
      when(mockFrontendRouteRepository.findRoutes(Some(service1), None, isDevhub = Some(true)))
        .thenReturn(
          Future.successful(
            Seq(
             MongoFrontendRoute(service1, "service1-frontend-route2", "path", Environment.Production, "", ruleConfigurationUrl = "github-devhub-link", isDevhub = true)
            )
          )
        )

      val result =
        call(
          controller.routes(Some(service1), None, Some(devhub)),
          FakeRequest(GET, "/routes/service1?routeType=devhub")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse(
        """[
        {
          "serviceName": "service1",
          "path": "service1-frontend-route2",
          "ruleConfigurationUrl": "github-devhub-link",
          "isRegex": false,
          "routeType": "devhub",
          "environment": "production"
        }
      ]"""
      )

  "shuttering-routes/:environment" should:
    "return frontend routes for shuttering" in new Setup:
      // when isDevhub equals false route type is a Frontend
      when(mockFrontendRouteRepository.findRoutes(None, Some(Environment.Production), isDevhub = Some(false)))
        .thenReturn(
          Future.successful(
            Seq(
              MongoFrontendRoute(
                service              = ServiceName("service-1"),
                frontendPath         = "service-1-route",
                backendPath          = "path",
                environment          = Environment.Production,
                routesFile           = "",
                ruleConfigurationUrl = "github-frontend-link",
                markerComments       = Set(""),
                shutterKillswitch    = Some(MongoShutterSwitch("", Some(503), Some(""), Some(""))),
                shutterServiceSwitch = Some(MongoShutterSwitch("", Some(503), Some(""), Some("")))
              )
            )
          )
        )

      val result =
        call(
          controller.shutteringRoutes(Environment.Production),
          FakeRequest(GET, "/shuttering-routes/production")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse("""
        [
          {
            "service": "service-1",
            "environment": "production",
            "routesFile": "",
            "routes": [
              {
                "routesFile": "",
                "markerComments": [""],
                "frontendPath": "service-1-route",
                "shutterKillswitch": {
                  "switchFile": "",
                  "statusCode": 503,
                  "errorPage": "",
                  "rewriteRule": ""
                },
                "shutterServiceSwitch": {
                  "switchFile": "",
                  "statusCode": 503,
                  "errorPage": "",
                  "rewriteRule": ""
                },
                "backendPath": "path",
                "isDevhub": false,
                "ruleConfigurationUrl": "github-frontend-link",
                "isRegex": false
              }
            ]
          }
        ]"""
      )

  "frontend-routes/search" should :
    "return frontend routes that contain frontend path search term" in new Setup:
      val searchTerm = "/foo"
      when(mockFrontendRouteRepository.searchByFrontendPath(searchTerm))
        .thenReturn(
          Future.successful(
            Seq(
              MongoFrontendRoute(
                service              = ServiceName("service-1"),
                frontendPath         = searchTerm,
                backendPath          = "path",
                environment          = Environment.Production,
                routesFile           = "",
                ruleConfigurationUrl = "github-frontend-link",
                markerComments       = Set(""),
                shutterKillswitch    = Some(MongoShutterSwitch("", Some(503), Some(""), Some(""))),
                shutterServiceSwitch = Some(MongoShutterSwitch("", Some(503), Some(""), Some("")))
              )
            )
          )
        )

      val result =
        call(
          controller.searchByFrontendPath(searchTerm),
          FakeRequest(GET, "/frontend-routes/search?frontendPath=foo")
        )

      status(result) shouldBe 200

      contentAsJson(result) shouldBe Json.parse("""
        [
          {
            "service": "service-1",
            "environment": "production",
            "routesFile": "",
            "routes": [
              {
                "routesFile": "",
                "markerComments": [""],
                "frontendPath": "/foo",
                "shutterKillswitch": {
                  "switchFile": "",
                  "statusCode": 503,
                  "errorPage": "",
                  "rewriteRule": ""
                },
                "shutterServiceSwitch": {
                  "switchFile": "",
                  "statusCode": 503,
                  "errorPage": "",
                  "rewriteRule": ""
                },
                "backendPath": "path",
                "isDevhub": false,
                "ruleConfigurationUrl": "github-frontend-link",
                "isRegex": false
              }
            ]
          }
        ]"""
      )

  trait Setup:
    given ActorSystem = ActorSystem()

    val mockAdminFrontendRouteRepository = mock[AdminFrontendRouteRepository]
    val mockFrontendRouteRepository      = mock[FrontendRouteRepository]

    val controller = RouteConfigurationController(
      frontendRouteRepository      = mockFrontendRouteRepository,
      adminFrontendRouteRepository = mockAdminFrontendRouteRepository,
      mcc                          = stubMessagesControllerComponents()
    )

end RouteConfigurationControllerSpec
