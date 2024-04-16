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

package uk.gov.hmrc.serviceconfigs.persistence

import cats.implicits._
import org.scalatest.concurrent.IntegrationPatience
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.{ExecutionContext, Future}

class FrontendRouteRepositoryMongoSpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[MongoFrontendRoute]
     with IntegrationPatience {

  import ExecutionContext.Implicits.global

  override lazy val repository = new FrontendRouteRepository(mongoComponent)

  override protected val checkIndexedQueries: Boolean =
    // we run unindexed queries
    false

  "FrontendRouteRepository.update" should {
    "add new route" in {
      val frontendRoute = newFrontendRoute()

      repository.update(frontendRoute).futureValue

      val allEntries = repository.findAllRoutes().futureValue
      allEntries should have size 1
      val createdRoute = allEntries.head
      createdRoute shouldBe frontendRoute
    }
  }

  "FrontendRouteRepository.findByService" should {
    "return only routes with the service" in {
      val service1Name = ServiceName("service1")
      val service2Name = ServiceName("service2")
      repository.update(newFrontendRoute(serviceName = service1Name)).futureValue
      repository.update(newFrontendRoute(serviceName = service2Name)).futureValue

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
      repository.update(newFrontendRoute(environment = Environment.Production)).futureValue
      repository.update(newFrontendRoute(environment = Environment.QA)).futureValue

      val allEntries = repository.findAllRoutes().futureValue
      allEntries should have size 2

      val productionEntries = repository.findByEnvironment(Environment.Production).futureValue
      productionEntries should have size 1
      val route = productionEntries.head
      route.environment shouldBe Environment.Production
    }
  }

  "FrontendRouteRepository.searchByFrontendPath" should {
    "return only routes with the path" in {
      addFrontendRoutes("a", "b").futureValue

      val service1Entries = repository.searchByFrontendPath("a").futureValue
      service1Entries.map(_.frontendPath).toList shouldBe List("a")
    }

    "FrontendRouteRepository.findAllFrontendServices" should {
      "return list of all services" in {
        repository.update(newFrontendRoute(serviceName = ServiceName("service1"), environment = Environment.Production )).futureValue
        repository.update(newFrontendRoute(serviceName = ServiceName("service1"), environment = Environment.Development)).futureValue
        repository.update(newFrontendRoute(serviceName = ServiceName("service2"), environment = Environment.Production )).futureValue
        repository.update(newFrontendRoute(serviceName = ServiceName("service2"), environment = Environment.Development)).futureValue

        val allEntries = findAll().futureValue

        allEntries should have size 4

        val services = repository.findAllFrontendServices().futureValue
        services should have size 2
        services shouldBe List(ServiceName("service1"), ServiceName("service2"))
      }
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

    "put and retrieve" in {
      val frontendRoute1 = newFrontendRoute(serviceName = ServiceName("service1"), frontendPath = "frontendPath1", environment = Environment.Production)
      val frontendRoute2 = newFrontendRoute(serviceName = ServiceName("service1"), frontendPath = "frontendPath2", environment = Environment.Production)
      val frontendRoute3 = newFrontendRoute(serviceName = ServiceName("service2"), frontendPath = "frontendPath3", environment = Environment.Production)
      repository.replaceEnv(Environment.Production, Set(frontendRoute1, frontendRoute2, frontendRoute3)).futureValue

      repository.findByService(frontendRoute1.service).futureValue shouldBe Seq(frontendRoute1, frontendRoute2)
      repository.findByService(frontendRoute3.service).futureValue shouldBe Seq(frontendRoute3)

      repository.replaceEnv(Environment.Production, Set(frontendRoute1.copy(frontendPath = "frontendPath4"))).futureValue
      repository.findByService(frontendRoute1.service).futureValue shouldBe Seq(frontendRoute1.copy(frontendPath = "frontendPath4"))
      repository.findByService(frontendRoute3.service).futureValue shouldBe empty
    }
  }

  def newFrontendRoute(
    serviceName : ServiceName = ServiceName("service"),
    frontendPath: String      = "frontendPath",
    environment : Environment = Environment.Production,
    isRegex     : Boolean     = true
  ) = MongoFrontendRoute(
    service              = serviceName,
    frontendPath         = frontendPath,
    backendPath          = "backendPath",
    environment          = environment,
    routesFile           = "file1",
    ruleConfigurationUrl = "rule1",
    shutterKillswitch    = None,
    shutterServiceSwitch = None,
    isRegex              = isRegex,
    updateDate           = Instant.now().truncatedTo(ChronoUnit.MILLIS)
  )

  def addFrontendRoutes(path: String*): Future[Unit] =
    path.toList
      .traverse(p => repository.update(newFrontendRoute(frontendPath = p)))
      .map(_ => ())
}
