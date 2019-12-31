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

import org.mongodb.scala.model.IndexModel
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.mongo.test.CleanMongoCollectionSupport
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.{ExecutionContext, Future}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class FrontendRouteRepositoryMongoSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterEach
    with MockitoSugar
    with CleanMongoCollectionSupport {

  import ExecutionContext.Implicits.global

  private val frontendRouteRepo = new FrontendRouteRepository(mongoComponent)

  "FrontendRouteRepo.update" should {
    "add new route" in {
      val frontendRoute = newFrontendRoute()

      frontendRouteRepo.update(frontendRoute).futureValue

      val allEntries = frontendRouteRepo.findAllRoutes().futureValue
      allEntries should have size 1
      val createdRoute = allEntries.head
      createdRoute shouldBe frontendRoute
    }
    "add a new route (not overwrite) when a route is the same but comes from a different file" in {
      val frontendRoute = newFrontendRoute()

      frontendRouteRepo.update(frontendRoute).futureValue
      frontendRouteRepo.update(frontendRoute.copy(ruleConfigurationUrl = "rule1", routesFile = "file2")).futureValue

      val allEntries = frontendRouteRepo.findAllRoutes().futureValue
      allEntries should have size 2
      val createdRoute = allEntries.head
      createdRoute shouldBe frontendRoute
    }
  }

  "FrontendRouteRepo.findByService" should {
    "return only routes with the service" in {
      val service1Name = "service1"
      val service2Name = "service2"
      frontendRouteRepo.update(newFrontendRoute(service = service1Name)).futureValue
      frontendRouteRepo.update(newFrontendRoute(service = service2Name)).futureValue

      val allEntries = frontendRouteRepo.findAllRoutes().futureValue
      allEntries should have size 2

      val service1Entries = frontendRouteRepo.findByService(service1Name).futureValue
      service1Entries should have size 1
      val service1Route = service1Entries.head
      service1Route.service shouldBe service1Name
    }
  }

  "FrontendRouteRepo.findByEnvironment" should {
    "return only routes with the environment" in {
      frontendRouteRepo.update(newFrontendRoute(environment = "production")).futureValue
      frontendRouteRepo.update(newFrontendRoute(environment = "qa")).futureValue

      val allEntries = frontendRouteRepo.findAllRoutes().futureValue
      allEntries should have size 2

      val productionEntries = frontendRouteRepo.findByEnvironment("production").futureValue
      productionEntries should have size 1
      val route = productionEntries.head
      route.environment shouldBe "production"
    }
  }

  "FrontendRouteRepo.searchByFrontendPath" should {
    "return only routes with the path" in {
      addFrontendRoutes("a", "b").futureValue

      val service1Entries = frontendRouteRepo.searchByFrontendPath("a").futureValue
      service1Entries.map(_.frontendPath).toList shouldBe List("a")
    }

    "return routes with the subpath" in {
      addFrontendRoutes("a/b/c", "a/b/d", "a/b", "a/bb").futureValue

      val service1Entries = frontendRouteRepo.searchByFrontendPath("a/b").futureValue
      service1Entries.map(_.frontendPath).toList.sorted shouldBe List(
        "a/b",
        "a/b/c",
        "a/b/d"
      )
    }

    "return routes with the parent path if no match" in {
      addFrontendRoutes("a/1", "b/1").futureValue

      val service1Entries = frontendRouteRepo.searchByFrontendPath("a/2").futureValue
      service1Entries.map(_.frontendPath).toList shouldBe List("a/1")
    }
  }

  def newFrontendRoute(
    service: String      = "service",
    frontendPath: String = "frontendPath",
    environment: String  = "environment",
    isRegex: Boolean     = true) =
    MongoFrontendRoute(
      service              = service,
      frontendPath         = frontendPath,
      backendPath          = "backendPath",
      environment          = environment,
      routesFile           = "file1",
      ruleConfigurationUrl = "rule1",
      shutterKillswitch    = None,
      shutterServiceSwitch = None,
      isRegex              = isRegex
    )

  def addFrontendRoutes(path: String*): Future[Unit] =
    Future
      .sequence(
        path.map(
          p => frontendRouteRepo.update(newFrontendRoute(frontendPath = p))
        )
      )
      .map(_ => ())

  override protected val collectionName: String   = frontendRouteRepo.collectionName
  override protected val indexes: Seq[IndexModel] = frontendRouteRepo.indexes
}
