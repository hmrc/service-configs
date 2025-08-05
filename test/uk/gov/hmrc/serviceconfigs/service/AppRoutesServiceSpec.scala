/*
 * Copyright 2025 HM Revenue & Customs
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

import play.api.Configuration
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatestplus.mockito.MockitoSugar
import org.mockito.Mockito.{when, verify}
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.model.{AppRoutes, RepoName, ServiceName, Version}
import uk.gov.hmrc.serviceconfigs.persistence.AppRoutesRepository

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class AppRoutesServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar:

  "AppRoutesService.update" should:
    "fetch, parse and store routes for a given service:version" in new Setup:
      when(configAsCode.getVersionedFileContent(RepoName("test-service"), "conf/prod.routes", Version(1, 0, 0)))
        .thenReturn(Future.successful(Some("""
        |->         /test-service  app.Routes
        |->         /              health.Routes
        |""".stripMargin)))

      when(configAsCode.getVersionedFileContent(RepoName("test-service"), "conf/app.routes", Version(1, 0, 0)))
        .thenReturn(Future.successful(Some("""
        |GET        /hello/:name   package.Controller.hello(name: String, title: Option[String] ?= None)
        |GET        /assets/*file  package.Controller.assets(path = "/public", file: Asset)
        |+nocsrf
        |POST       /audit         package.Controller.audit()
        |""".stripMargin)))

      when(appRoutesRepo.put(any[AppRoutes])).thenReturn(Future.unit)

      service.update(ServiceName("test-service"), Version(1, 0, 0)).futureValue shouldBe ()

      val captor = ArgumentCaptor.forClass(classOf[AppRoutes])
      verify(appRoutesRepo).put(captor.capture())

      val actualAppRoutes = captor.getValue

      actualAppRoutes.service shouldBe ServiceName("test-service")
      actualAppRoutes.version shouldBe Version(1, 0, 0)
      actualAppRoutes.routes should have length 3

      val routesByPath = actualAppRoutes.routes.groupBy(_.path).view.mapValues(_.head).toMap

      val helloRoute = routesByPath("/test-service/hello/:name")
      helloRoute.verb shouldBe "GET"
      helloRoute.controller shouldBe "package.Controller"
      helloRoute.method shouldBe "hello"
      helloRoute.parameters should have length 2

      val nameParam = helloRoute.parameters.find(_.name == "name").get
      nameParam.typeName shouldBe "String"
      nameParam.isPathParam shouldBe true
      nameParam.isQueryParam shouldBe false

      val titleParam = helloRoute.parameters.find(_.name == "title").get
      titleParam.typeName shouldBe "Option[String]"
      titleParam.isPathParam shouldBe false
      titleParam.isQueryParam shouldBe true
      titleParam.default shouldBe Some("None")

      val assetsRoute = routesByPath("/test-service/assets/*file")
      assetsRoute.verb shouldBe "GET"
      assetsRoute.controller shouldBe "package.Controller"
      assetsRoute.method shouldBe "assets"
      assetsRoute.parameters should have length 2
      assetsRoute.parameters.find(_.name == "path").get.fixed shouldBe Some("\"/public\"")

      val auditRoute = routesByPath("/test-service/audit")
      auditRoute.verb shouldBe "POST"
      auditRoute.controller shouldBe "package.Controller"
      auditRoute.method shouldBe "audit"
      auditRoute.parameters shouldBe empty
      auditRoute.modifiers should contain("nocsrf")

  trait Setup:
    val appRoutesRepo = mock[AppRoutesRepository]
    val configAsCode  = mock[ConfigAsCodeConnector]
    val configuration = Configuration(
      "app-routes.libraryIncludes" -> Seq("health.Routes")
    )

    val service = AppRoutesService(appRoutesRepo, configAsCode, configuration)
