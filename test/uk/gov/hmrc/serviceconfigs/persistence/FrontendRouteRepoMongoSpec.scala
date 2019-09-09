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
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar
import play.modules.reactivemongo.ReactiveMongoComponent
import uk.gov.hmrc.mongo.{
  MongoConnector,
  MongoSpecSupport,
  RepositoryPreparation
}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.{ExecutionContext, Future}

class FrontendRouteRepoMongoSpec
    extends WordSpecLike
    with Matchers
    with MongoSpecSupport
    with ScalaFutures
    with BeforeAndAfterEach
    with MockitoSugar
    //with FailOnUnindexedQueries
    with RepositoryPreparation {

  import ExecutionContext.Implicits.global

  val reactiveMongoComponent: ReactiveMongoComponent =
    new ReactiveMongoComponent {
      override val mongoConnector = {
        val mc = mock[MongoConnector]
        when(mc.db).thenReturn(mongo)
        mc
      }
    }

  val frontendRouteRepo = new FrontendRouteRepo(reactiveMongoComponent)

  override def beforeEach() {
    prepare(frontendRouteRepo)
  }

  "FrontendRouteRepo.update" should {
    "add new route" in {
      val frontendRoute = newFrontendRoute(service = "service")

      await(frontendRouteRepo.update(frontendRoute))

      val allEntries = await(frontendRouteRepo.findAllRoutes())
      allEntries should have size 1
      val createdRoute = allEntries.head
      createdRoute shouldBe frontendRoute
    }
    "add a new route (not overwrite) when a route is the same but comes from a different file" in {
      val frontendRoute = newFrontendRoute(service = "service")

      await(frontendRouteRepo.update(frontendRoute))
      await(frontendRouteRepo.update(frontendRoute.copy(ruleConfigurationUrl = "rule1", routesFile = "file2")))

      val allEntries = await(frontendRouteRepo.findAllRoutes())
      allEntries should have size 2
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

      val allEntries = await(frontendRouteRepo.findAllRoutes())
      allEntries should have size 2

      val service1Entries = await(frontendRouteRepo.findByService(service1Name))
      service1Entries should have size 1
      val service1Route = service1Entries.head
      service1Route.service shouldBe service1Name
    }
  }

  "FrontendRouteRepo.findByEnvironment" should {
    "return only routes with the environment" in {
      await(frontendRouteRepo.update(newFrontendRoute(environment = "production")))
      await(frontendRouteRepo.update(newFrontendRoute(environment = "qa")))

      val allEntries = await(frontendRouteRepo.findAllRoutes())
      allEntries should have size 2

      val productionEntries = await(frontendRouteRepo.findByEnvironment("production"))
      productionEntries should have size 1
      val route = productionEntries.head
      route.environment shouldBe "production"
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
      service1Entries.map(_.frontendPath).toList.sorted shouldBe List(
        "a/b",
        "a/b/c",
        "a/b/d"
      )
    }

    "return routes with the parent path if no match" in {
      await(addFrontendRoutes("a/1", "b/1"))

      val service1Entries = await(frontendRouteRepo.searchByFrontendPath("a/2"))
      service1Entries.map(_.frontendPath).toList shouldBe List("a/1")
    }
  }

  def newFrontendRoute(service: String = "service",
                       frontendPath: String = "frontendPath",
                       environment: String = "environment",
                       isRegex: Boolean = true) =
    MongoFrontendRoute(
      service = service,
      frontendPath = frontendPath,
      backendPath = "backendPath",
      environment = environment,
      routesFile = "file1",
      ruleConfigurationUrl = "rule1",
      shutterKillswitch = None,
      shutterServiceSwitch = None,
      isRegex = isRegex,
      updateDate = DateTime.now(DateTimeZone.UTC)
    )

  def addFrontendRoutes(path: String*): Future[Unit] =
    Future
      .sequence(
        path.map(
          p => frontendRouteRepo.update(newFrontendRoute(frontendPath = p))
        )
      )
      .map(_ => ())
}
