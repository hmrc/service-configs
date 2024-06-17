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

package uk.gov.hmrc.serviceconfigs.model

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

class ParserFrontendRoutesSpec
  extends AnyWordSpec
     with Matchers:

  "frontendRoutes" should:
    "group routes by environment" in:
      val mongRoutes = Seq(
        MongoFrontendRoute(ServiceName("testService"), "/test1", "http://test.com",  Environment.Production , routesFile = "file1"),
        MongoFrontendRoute(ServiceName("testService"), "/test2", "http://test2.com", Environment.Production , routesFile = "file1"),
        MongoFrontendRoute(ServiceName("testService"), "/test1", "http://test.com",  Environment.Development, routesFile = "file1"),
        MongoFrontendRoute(ServiceName("testService"), "/test2", "http://test2.com", Environment.Development, routesFile = "file1"),
        MongoFrontendRoute(ServiceName("testService"), "/test3", "http://test3.com", Environment.Development, routesFile = "file1"))

      val res = FrontendRoutes.fromMongo(mongRoutes)
      res.size shouldBe 2

      res.exists(_.environment == Environment.Production) shouldBe true
      val prod = res.find(_.environment == Environment.Production).get
      prod.routes.contains( FrontendRoute("/test1", "http://test.com")) shouldBe true
      prod.routes.contains( FrontendRoute("/test2", "http://test2.com")) shouldBe true


      res.exists(_.environment == Environment.Development) shouldBe true
      val dev = res.find(_.environment == Environment.Development).get
      dev.routes.contains( FrontendRoute("/test1", "http://test.com")) shouldBe true
      dev.routes.contains( FrontendRoute("/test2", "http://test2.com")) shouldBe true
      dev.routes.contains(FrontendRoute("/test3", "http://test3.com")) shouldBe true

  "frontendRoute" should:
    "be creatable from a MongoFrontendRoute" in:
      val mongoRoute = MongoFrontendRoute(ServiceName("testService"), "/test1", "http://test.com", Environment.Production, routesFile = "file1")
      val route      = FrontendRoute.fromMongo(mongoRoute)
      route shouldBe FrontendRoute(frontendPath = "/test1", backendPath = "http://test.com")
