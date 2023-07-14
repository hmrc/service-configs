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
import uk.gov.hmrc.serviceconfigs.model.{ServiceName, ServiceRelationship, ServiceRelationships, SlugDependency, SlugInfo, Version}
import uk.gov.hmrc.serviceconfigs.parser.MyConfigValue
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

  private val mockConfigService    = mock[ConfigService]
  private val mockRelationshipRepo = mock[ServiceRelationshipRepository]
  private val service              = new ServiceRelationshipService(mockConfigService, mock[SlugInfoRepository], mockRelationshipRepo)

  "serviceRelationships" should {
    "return inbound and outbound services for a given service" in {
      when(mockRelationshipRepo.getInboundServices(any[ServiceName])).thenReturn(Future.successful(Seq(ServiceName("service-b"), ServiceName("service-c"))))
      when(mockRelationshipRepo.getOutboundServices(any[ServiceName])).thenReturn(Future.successful(Seq(ServiceName("service-d"), ServiceName("service-e"))))

      val expected = ServiceRelationships(
        inboundServices  = Set(ServiceName("service-b"), ServiceName("service-c")),
        outboundServices = Set(ServiceName("service-d"), ServiceName("service-e"))
      )

      val result = service.getServiceRelationships(ServiceName("service-a")).futureValue

      result.inboundServices should contain theSameElementsAs expected.inboundServices
      result.outboundServices should contain theSameElementsAs expected.outboundServices
    }
  }

  private val dummyServiceName = ServiceName("service")

  private def dummySlugInfo(appConf: String = "not empty", baseConf: String = ""): SlugInfo =
    SlugInfo(
      uri = "",
      created           = Instant.now(),
      name              = dummyServiceName,
      version           = Version("0.0.1"),
      classpath         = "",
      dependencies      = List.empty[SlugDependency],
      applicationConfig = appConf,
      includedAppConfig = Map.empty,
      loggerConfig      = "",
      slugConfig        = baseConf
    )

  "serviceRelationshipsFromSlugInfo" should {
    "return an empty sequence when applicationConfig is empty" in {
      service.serviceRelationshipsFromSlugInfo(dummySlugInfo(appConf = ""), Seq.empty).futureValue shouldBe Seq.empty[ServiceRelationship]
    }

    "produce a ServiceRelationship for each downstream that's defined in config under microservice.services" in {
      val config = configSourceEntries(Map(
        "microservice.services.artefact-processor.host"     -> "localhost",
        "microservice.services.auth.host"                   -> "localhost",
        "microservice.services.service-dependencies.host"   -> "localhost",
        "microservice.services.releases-api.host"           -> "localhost",
        "microservice.services.teams-and-repositories.host" -> "localhost",
      ))

      when(mockConfigService.appConfig(any[SlugInfo])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Seq(config)))

      val knownServices = Seq(
        "artefact-processor",
        "auth",
        "service-dependencies",
        "releases-api",
        "teams-and-repositories"
      )

      val expected = Seq(
        ServiceRelationship(dummyServiceName, ServiceName("artefact-processor")),
        ServiceRelationship(dummyServiceName, ServiceName("auth")),
        ServiceRelationship(dummyServiceName, ServiceName("service-dependencies")),
        ServiceRelationship(dummyServiceName, ServiceName("releases-api")),
        ServiceRelationship(dummyServiceName, ServiceName("teams-and-repositories"))
      )

      service.serviceRelationshipsFromSlugInfo(dummySlugInfo(), knownServices).futureValue should contain theSameElementsAs expected
    }

    "extract the value from slugInfo.slugConfig if the value from appConf key isn't recognised" in {
      val config = configSourceEntries(Map(
        "microservice.services.auth.host"                    -> "localhost",
        "microservice.services.cacheable.session-cache.host" -> "localhost"
      ))

      when(mockConfigService.appConfig(any[SlugInfo])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Seq(config)))

      val knownServices = Seq("auth", "key-store")
      val baseConf =
        """
          |microservice {
          |  services {
          |    cacheable {
          |      session-cache {
          |        protocol = "https"
          |        host = key-store.public.mdtp
          |        port = 443
          |        domain = keystore
          |      }
          |    }
          |  }
          |}
          |""".stripMargin

      val expected = Seq(
        ServiceRelationship(dummyServiceName, ServiceName("auth")),
        ServiceRelationship(dummyServiceName, ServiceName("key-store"))
      )

      service.serviceRelationshipsFromSlugInfo(dummySlugInfo(baseConf = baseConf), knownServices).futureValue should contain theSameElementsAs expected
    }

    "use the value from appConf key if the value found in baseConf is unrecognised" in {
      val config = configSourceEntries(Map(
        "microservice.services.auth.host" -> "localhost",
        "microservice.services.des.host"  -> "localhost"
      ))

      when(mockConfigService.appConfig(any[SlugInfo])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Seq(config)))

      val knownServices = Seq("auth")
      val baseConf =
        """
          |microservice {
          |  services {
          |    des {
          |      host = des.ws.hmrc.gov.uk
          |      port = 443
          |      protocol = https
          |    }
          |  }
          |}
          |""".stripMargin

      val expected = Seq(
        ServiceRelationship(dummyServiceName, ServiceName("auth")),
        ServiceRelationship(dummyServiceName, ServiceName("des"))
      )

      service.serviceRelationshipsFromSlugInfo(dummySlugInfo(baseConf = baseConf), knownServices).futureValue should contain theSameElementsAs expected
    }

    "ignore stub/stubs hosts in baseConf and use the value from appConf key instead" in {
      val config = configSourceEntries(Map(
        "microservice.services.auth.host" -> "localhost",
        "microservice.services.des.host" -> "localhost"
      ))

      when(mockConfigService.appConfig(any[SlugInfo])(any[HeaderCarrier]))
        .thenReturn(Future.successful(Seq(config)))

      val knownServices = Seq("auth")
      val baseConf =
        """
          |microservice {
          |  services {
          |    des {
          |      host = test-stub.protected.mdtp
          |    }
          |  }
          |}
          |""".stripMargin

      val expected = Seq(
        ServiceRelationship(dummyServiceName, ServiceName("auth")),
        ServiceRelationship(dummyServiceName, ServiceName("des"))
      )

      service.serviceRelationshipsFromSlugInfo(dummySlugInfo(baseConf = baseConf), knownServices).futureValue should contain theSameElementsAs expected
    }

  }

  def configSourceEntries(entries: Map[String, String]): ConfigSourceEntries =
    ConfigSourceEntries(
      source    = "applicationConf",
      sourceUrl = None,
      entries   = entries.view.mapValues(MyConfigValue.apply).toMap
    )
}
