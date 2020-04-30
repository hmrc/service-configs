/*
 * Copyright 2020 HM Revenue & Customs
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

import cats.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.mongo.test.{CleanMongoCollectionSupport, PlayMongoRepositorySupport}

import scala.concurrent.duration.DurationInt

import scala.concurrent.{ExecutionContext, Future}

class FrontendRouteRepositoryMongoSpec
    extends AnyWordSpecLike
    with Matchers
    // not using DefaultPlayMongoRepositorySupport since we run unindexed queries
    with PlayMongoRepositorySupport[MongoFrontendRoute]
    with CleanMongoCollectionSupport {

  import ExecutionContext.Implicits.global

  override lazy val repository = new FrontendRouteRepository(mongoComponent)

  override implicit val patienceConfig = PatienceConfig(timeout = 30.seconds, interval = 100.millis)

  "FrontendRouteRepository.update" should {
    "add new route" in {
      val frontendRoute = newFrontendRoute()

      repository.update(frontendRoute).futureValue

      val allEntries = repository.findAllRoutes().futureValue
      allEntries should have size 1
      val createdRoute = allEntries.head
      createdRoute shouldBe frontendRoute
    }

    "add a new route (not overwrite) when a route is the same but comes from a different file" in {
      val frontendRoute = newFrontendRoute()

      repository.update(frontendRoute).futureValue
      repository.update(frontendRoute.copy(ruleConfigurationUrl = "rule1", routesFile = "file2")).futureValue

      val allEntries = repository.findAllRoutes().futureValue
      allEntries should have size 2
      val createdRoute = allEntries.head
      createdRoute shouldBe frontendRoute
    }
  }

  "FrontendRouteRepository.findByService" should {
    "return only routes with the service" in {
      val service1Name = "service1"
      val service2Name = "service2"
      repository.update(newFrontendRoute(service = service1Name)).futureValue
      repository.update(newFrontendRoute(service = service2Name)).futureValue

      val allEntries = repository.findAllRoutes().futureValue
      allEntries should have size 2

      val service1Entries = repository.findByService(service1Name).futureValue
      service1Entries should have size 1
      val service1Route = service1Entries.head
      service1Route.service shouldBe service1Name
    }
  }

  "FrontendRouteRepository.findByEnvironment" should {
    "return only routes with the environment" in {
      repository.update(newFrontendRoute(environment = "production")).futureValue
      repository.update(newFrontendRoute(environment = "qa")).futureValue

      val allEntries = repository.findAllRoutes().futureValue
      allEntries should have size 2

      val productionEntries = repository.findByEnvironment("production").futureValue
      productionEntries should have size 1
      val route = productionEntries.head
      route.environment shouldBe "production"
    }
  }

  "FrontendRouteRepository.searchByFrontendPath" should {
    "return only routes with the path" in {
      addFrontendRoutes("a", "b").futureValue

      val service1Entries = repository.searchByFrontendPath("a").futureValue
      service1Entries.map(_.frontendPath).toList shouldBe List("a")
    }

    "return routes with the subpath" in {
      addFrontendRoutes("a/b/c", "a/b/d", "a/b", "a/bb").futureValue

      val service1Entries = repository.searchByFrontendPath("a/b").futureValue
      service1Entries.map(_.frontendPath).toList.sorted shouldBe List(
        "a/b",
        "a/b/c",
        "a/b/d"
      )
    }

    "return routes with the parent path if no match" in {
      addFrontendRoutes("a/1", "b/1").futureValue

      val service1Entries = repository.searchByFrontendPath("a/2").futureValue
      service1Entries.map(_.frontendPath).toList shouldBe List("a/1")
    }
  }

  def newFrontendRoute(
    service     : String  = "service",
    frontendPath: String  = "frontendPath",
    environment : String  = "environment",
    isRegex     : Boolean = true
  ) =
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
    path.toList
      .traverse(p => repository.update(newFrontendRoute(frontendPath = p)))
      .map(_ => ())
}
