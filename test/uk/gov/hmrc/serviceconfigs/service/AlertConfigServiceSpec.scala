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


import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{times, verify, when}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.serviceconfigs.connector.{ArtifactoryConnector, ConfigAsCodeConnector}
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, RepoName, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.{AlertEnvironmentHandlerRepository, LastHashRepository}

import java.io.FileInputStream
import java.util.zip.ZipInputStream
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class AlertConfigServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar:

  import AlertConfigService._

  "AlertConfigService.update" should:
    "update configs when run for the first time" in new Setup:
      when(mockArtifactoryConnector.getLatestHash()              ).thenReturn(Future.successful(Some("Test1")))
      when(mockLastHashRepository.getHash("alert-config")        ).thenReturn(Future.successful(None))
      when(mockArtifactoryConnector.getSensuZip()                ).thenReturn(Future.successful(ZipInputStream(this.getClass.getResource("/output.zip").openStream())))
      when(mockConfigAsCodeConnector.streamAlertConfig()         ).thenReturn(Future.successful(ZipInputStream(this.getClass.getResource("/alert-config.zip").openStream())))
      when(mockAlertEnvironmentHandlerRepository.putAll(any)     ).thenReturn(Future.unit)
      when(mockLastHashRepository.update("alert-config", "Test1")).thenReturn(Future.unit)

      alertConfigService.update().futureValue

      verify(mockArtifactoryConnector             , times(1)).getLatestHash()
      verify(mockLastHashRepository               , times(1)).getHash("alert-config")
      verify(mockAlertEnvironmentHandlerRepository, times(1)).putAll(any[Seq[AlertEnvironmentHandler]])
      verify(mockLastHashRepository               , times(1)).update("alert-config", "Test1")

    "update configs when Artifactory returns a new Hash" in new Setup:
      when(mockArtifactoryConnector.getLatestHash()              ).thenReturn(Future.successful(Some("Test2")))
      when(mockLastHashRepository.getHash("alert-config")        ).thenReturn(Future.successful(Some("Test1")))
      when(mockArtifactoryConnector.getSensuZip()                ).thenReturn(Future.successful(ZipInputStream(this.getClass.getResource("/output.zip").openStream())))
      when(mockConfigAsCodeConnector.streamAlertConfig()         ).thenReturn(Future.successful(ZipInputStream(this.getClass.getResource("/alert-config.zip").openStream())))
      when(mockAlertEnvironmentHandlerRepository.putAll(any)     ).thenReturn(Future.unit)
      when(mockLastHashRepository.update("alert-config", "Test2")).thenReturn(Future.unit)

      alertConfigService.update().futureValue

      verify(mockAlertEnvironmentHandlerRepository, times(1)).putAll(any[Seq[AlertEnvironmentHandler]])
      verify(mockLastHashRepository               , times(1)).update("alert-config", "Test2")

    "return successful when Artifactory has no updated Hash" in new Setup:
      when(mockArtifactoryConnector.getLatestHash()      ).thenReturn(Future.successful(Some("Test3")))
      when(mockLastHashRepository.getHash("alert-config")).thenReturn(Future.successful(Some("Test3")))

      alertConfigService.update().futureValue

  "AlertConfigService.processZip" should:
    "produce a AlertConfig that contains app name and handler name" in:
      val zip  = ZipInputStream(FileInputStream("./test/resources/happy-output.zip"))
      val file = processZip(zip)

      file.serviceConfigs.exists(_.serviceName == ServiceName("accessibility-statement-frontend")) shouldBe true
      file.serviceConfigs.exists(_.serviceName == ServiceName("add-taxes-frontend")) shouldBe true

      file.productionHandlers.get(PagerDutyKey("yta"))         shouldBe Some(Handler("/etc/sensu/handlers/hmrc_pagerduty_multiteam_env_apiv2.rb --team yta -e aws_production"))
      file.productionHandlers.get(PagerDutyKey("platform-ui")) shouldBe Some(Handler("/etc/sensu/handlers/hmrc_pagerduty_multiteam_env_apiv2.rb --team platform-ui -e aws_production"))

    "produce a AlertConfig that contains app name and None, when handler does not exist" in:
      val zip  = ZipInputStream(FileInputStream("./test/resources/missing-handler-output.zip"))
      val file = processZip(zip)

      file.serviceConfigs.exists(_.serviceName == ServiceName("accessibility-statement-frontend")) shouldBe true
      file.serviceConfigs.exists(_.serviceName == ServiceName("add-taxes-frontend")) shouldBe true

      file.productionHandlers.get(PagerDutyKey("yta"))         shouldBe None
      file.productionHandlers.get(PagerDutyKey("platform-ui")) shouldBe Some(Handler("/etc/sensu/handlers/hmrc_pagerduty_multiteam_env_apiv2.rb --team platform-ui -e aws_production"))

  "AlertConfigService.toAlertEnvironmentHandler" should:
    val locations = Seq(RepoName("test") -> "line1")
    "produce an AlertEnvironmentHandler for a service that has alert config enabled" in:
      val alertConfig = AlertConfig(
        Seq(ServiceConfig(ServiceName("test"), Seq(PagerDutyKey("TEST")))),
        Map(PagerDutyKey("TEST") -> Handler("test/command"))
      )
      toAlertEnvironmentHandler(alertConfig, locations) shouldBe List(AlertEnvironmentHandler(ServiceName("test"), production = true, location = "line1"))

    "produce an AlertEnvironmentHandler for a service that has Alert Config Disabled" in:
      val alertConfig = AlertConfig(
        Seq(ServiceConfig(ServiceName("test"), Seq(PagerDutyKey("TEST")))),
        Map(PagerDutyKey("TEST") -> Handler("test/noop.rb"))
      )
      toAlertEnvironmentHandler(alertConfig, locations) shouldBe List(AlertEnvironmentHandler(ServiceName("test"), production = false, location = "line1"))

    "produce an AlertEnvironmentHandler when a service has No Matching Handler Found in Production" in:
      val alertConfig = AlertConfig(
        Seq(ServiceConfig(ServiceName("test"), Seq(PagerDutyKey("TEST")))),
        Map.empty
      )
      toAlertEnvironmentHandler(alertConfig, locations) shouldBe List(AlertEnvironmentHandler(ServiceName("test"), production = false, location = "line1"))

    "produce an AlertEnvironmentHandler for a service that has No Handler Name Defined in Config" in:
      val alertConfig = AlertConfig(
        Seq(ServiceConfig(ServiceName("test"), Nil)),
        Map(PagerDutyKey("TEST") -> Handler("test/command"))
      )
      toAlertEnvironmentHandler(alertConfig, locations) shouldBe List(AlertEnvironmentHandler(ServiceName("test"), production = false, location = "line1"))

    "produce an empty list when there is No Existing Alert Config" in:
      val alertConfig = AlertConfig(
        Seq(),
        Map(PagerDutyKey("TEST") -> Handler("test/command"))
      )
      toAlertEnvironmentHandler(alertConfig, Nil) shouldBe List.empty

  trait Setup:
    lazy val mockLastHashRepository                = mock[LastHashRepository]
    lazy val mockAlertEnvironmentHandlerRepository = mock[AlertEnvironmentHandlerRepository]
    lazy val mockArtifactoryConnector              = mock[ArtifactoryConnector]
    lazy val mockConfigAsCodeConnector             = mock[ConfigAsCodeConnector]

    val alertConfigService = AlertConfigService(
      mockAlertEnvironmentHandlerRepository,
      mockLastHashRepository,
      mockArtifactoryConnector,
      mockConfigAsCodeConnector
    )
