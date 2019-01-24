/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.persistence

import org.joda.time.{DateTime, DateTimeZone}
import org.mockito.Mockito.when
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterEach, LoneElement, OptionValues}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.play.test.UnitSpec
import uk.gov.hmrc.serviceconfigs.model.{FrontendRoute, FrontendRoutes}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.{ExecutionContext, Future}

class FrontendRouteRepoMongoSpec
    extends UnitSpec
       with LoneElement
       with MongoSpecSupport
       with ScalaFutures
       with OptionValues
       with BeforeAndAfterEach
       with GuiceOneAppPerSuite
       with MockitoSugar {

  import ExecutionContext.Implicits.global

  val reactiveMongoComponent: ReactiveMongoComponent = new ReactiveMongoComponent {
    val mockedMongoConnector: MongoConnector = mock[MongoConnector]
    when(mockedMongoConnector.db).thenReturn(mongo)

    override def mongoConnector = mockedMongoConnector
  }

  override def fakeApplication(): Application = GuiceApplicationBuilder()
    .configure("metrics.jvm" -> false)
    .build()

  val frontendRouteRepo = new FrontendRouteRepo(reactiveMongoComponent)

  override def beforeEach() {
    await(frontendRouteRepo.drop)
  }

  "FrontendRouteRepo.update" should {
    "add new route" in {
      val frontendRoute = newFrontendRoute(service = "service")

      await(frontendRouteRepo.update(frontendRoute))

      val allEntries = await(frontendRouteRepo.findAllRoutes)
      allEntries should have size 1
      val createdRoute = allEntries.head
      createdRoute shouldBe frontendRoute
    }
  }

  "FrontendRouteRepo.findByService" should {
    "return only routes with the service" in {
      val service1Name = "service1"
      val service2Name = "service2"
      await(frontendRouteRepo.update(newFrontendRoute(service = service1Name)))
      await(frontendRouteRepo.update(newFrontendRoute(service = service2Name)))

      val allEntries = await(frontendRouteRepo.findAllRoutes)
      allEntries should have size 2

      val service1Entries = await(frontendRouteRepo.findByService(service1Name))
      service1Entries should have size 1
      val service1Route = service1Entries.head
      service1Route.service shouldBe service1Name
    }
  }


  "FrontendRouteRepo.searchByFrontendPath" should {
    "return only routes with the path" in {
      await(addFrontendRoutes("a", "b"))

      val service1Entries = await(frontendRouteRepo.searchByFrontendPath("a"))
      service1Entries.map(_.frontendPath).toList shouldBe List("a")
    }

    "return routes with the subpath" in {
      await(addFrontendRoutes("a/b/c", "a/b/d", "a/b", "a/bb"))

      val service1Entries = await(frontendRouteRepo.searchByFrontendPath("a/b"))
      service1Entries.map(_.frontendPath).toList.sorted shouldBe List("a/b", "a/b/c", "a/b/d")
    }

    "return routes with the parent path if no match" in {
      await(addFrontendRoutes("a/1", "b/1"))

      val service1Entries = await(frontendRouteRepo.searchByFrontendPath("a/2"))
      service1Entries.map(_.frontendPath).toList shouldBe List("a/1")
    }
  }


  def newFrontendRoute(service: String = "service", frontendPath: String = "frontendPath", isRegex: Boolean = true) =
    MongoFrontendRoute(
        service      = service,
        frontendPath = frontendPath,
        backendPath  = "backendPath",
        environment  = "environment",
        ruleConfigurationUrl = "",
        isRegex      = isRegex,
        updateDate   = DateTime.now(DateTimeZone.UTC))

  def addFrontendRoutes(path: String*): Future[Unit] =
    Future.sequence(path.map(p => frontendRouteRepo.update(newFrontendRoute(frontendPath = p))))
      .map(_ => ())
}
