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

package uk.gov.hmrc.serviceconfigs.service

import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.model.{ServiceRelationship, SlugDependency, SlugInfo, Version}
import uk.gov.hmrc.serviceconfigs.persistence.{ServiceRelationshipRepository, SlugInfoRepository}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.ConfigSourceEntries

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ServiceRelationshipServiceSpec
  extends AnyWordSpec
  with Matchers
  with ScalaFutures
  with MockitoSugar {

  private val mockConfigService = mock[ConfigService]
  private val service = new ServiceRelationshipService(mockConfigService, mock[SlugInfoRepository], mock[ServiceRelationshipRepository])

  private def dummySlugInfo(name: String = "service-a", appConf: String = "not empty"): SlugInfo =
    SlugInfo(
      uri = "",
      created = Instant.now(),
      name = name,
      version = Version("0.0.1"),
      classpath = "",
      dependencies = List.empty[SlugDependency],
      applicationConfig = appConf,
      loggerConfig = "",
      slugConfig = ""
    )

  "serviceRelationshipsFromSlugInfo" should {
    "return an empty sequence when applicationConfig is empty" in {
      service.serviceRelationshipsFromSlugInfo(dummySlugInfo(appConf = "")).futureValue shouldBe Seq.empty[ServiceRelationship]
    }

    "produce a ServiceRelationship for each downstream that's defined in config under microservice.services" in {
      val mockedConfig = ConfigSourceEntries(
        source = "applicationConf",
        entries = Map(
          "microservice.services.artefact-processor.host"     -> "localhost",
          "microservice.services.artefact-processor.port"     -> "port",
          "microservice.services.auth.host"                   -> "localhost",
          "microservice.services.auth.port"                   -> "port",
          "microservice.services.service-dependencies.host"   -> "localhost",
          "microservice.services.service-dependencies.port"   -> "port",
          "microservice.services.releases-api.host"           -> "localhost",
          "microservice.services.releases-api.port"           -> "port",
          "microservice.services.teams-and-repositories.host" -> "localhost",
          "microservice.services.teams-and-repositories.port" -> "port",
        )
      )

      when(mockConfigService.appConfig(any[SlugInfo])(any[HeaderCarrier])).thenReturn(Future.successful(Seq(mockedConfig)))

      val expected = Seq(
        ServiceRelationship("service-a", "artefact-processor"),
        ServiceRelationship("service-a", "auth"),
        ServiceRelationship("service-a", "service-dependencies"),
        ServiceRelationship("service-a", "releases-api"),
        ServiceRelationship("service-a", "teams-and-repositories")
      )

      service.serviceRelationshipsFromSlugInfo(dummySlugInfo()).futureValue should contain theSameElementsAs expected
    }

    "ignore config that does not have host and port" in {
      val mockedConfig = ConfigSourceEntries(
        source = "applicationConf",
        entries = Map(
          "microservice.services.artefact-processor.url"      -> "ignore me",
          "microservice.services.auth.host"                   -> "localhost",
          "microservice.services.auth.port"                   -> "port",
          "microservice.services.service-dependencies.host"   -> "localhost",
          "microservice.services.service-dependencies.port"   -> "port",
          "microservice.services.releases-api.host"           -> "localhost",
          "microservice.services.releases-api.port"           -> "port",
          "microservice.services.teams-and-repositories.host" -> "localhost",
          "microservice.services.teams-and-repositories.port" -> "port",
        )
      )

      when(mockConfigService.appConfig(any[SlugInfo])(any[HeaderCarrier])).thenReturn(Future.successful(Seq(mockedConfig)))

      val expected = Seq(
        ServiceRelationship("service-a", "auth"),
        ServiceRelationship("service-a", "service-dependencies"),
        ServiceRelationship("service-a", "releases-api"),
        ServiceRelationship("service-a", "teams-and-repositories")
      )

      service.serviceRelationshipsFromSlugInfo(dummySlugInfo()).futureValue should contain theSameElementsAs expected
    }

  }

}
