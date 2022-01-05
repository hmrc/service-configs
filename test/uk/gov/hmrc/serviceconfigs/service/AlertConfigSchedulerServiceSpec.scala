/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.serviceconfigs.connector.ArtifactoryConnector
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, LastHash}
import uk.gov.hmrc.serviceconfigs.persistence.{AlertEnvironmentHandlerRepository, AlertHashStringRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import java.io.FileInputStream
import java.net.URL
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class AlertConfigSchedulerServiceSpec extends AnyWordSpec with Matchers
  with ScalaFutures with MockitoSugar {


  "AlertConfigSchedulerService.updateConfigs" should {
    "update configs when run for the first time" in new Setup {

      val res: URL = this.getClass.getResource("/output.zip")
      when(mockArtifactoryConnector.getLatestHash()).thenReturn(Future.successful(Some("Test1")))
      when(mockAlertHashRepository.findOne()).thenReturn(Future.successful(None))
      when(mockArtifactoryConnector.getSensuZip()).thenReturn(Future.successful(res.openStream()))
      when(mockAlertEnvironmentHandlerRepository.deleteAll()).thenReturn(Future.successful(()))
      when(mockAlertEnvironmentHandlerRepository.insert(any[Seq[AlertEnvironmentHandler]])).thenReturn(Future.successful(()))
      when(mockAlertHashRepository.update(eqTo("Test1"))).thenReturn(Future.successful(()))


      val result: Future[Unit] = Await.ready(alertConfigSchedulerService.updateConfigs(), Duration(20, "seconds"))


      verify(mockArtifactoryConnector, times(1)).getLatestHash()
      verify(mockAlertHashRepository, times(1)).findOne()
      verify(mockAlertEnvironmentHandlerRepository, times(1)).deleteAll()
      verify(mockAlertEnvironmentHandlerRepository, times(1)).insert(any[Seq[AlertEnvironmentHandler]])
      verify(mockAlertHashRepository, times(1)).update(eqTo("Test1"))

      result.value.get.isSuccess shouldBe true
    }

    "update configs when Artifactory returns a new Hash" in new Setup {


      val res: URL = this.getClass.getResource("/output.zip")
      when(mockArtifactoryConnector.getLatestHash()).thenReturn(Future.successful(Some("Test2")))
      when(mockAlertHashRepository.findOne()).thenReturn(Future.successful(Some(LastHash("Test1"))))
      when(mockArtifactoryConnector.getSensuZip()).thenReturn(Future.successful(res.openStream()))
      when(mockAlertEnvironmentHandlerRepository.deleteAll()).thenReturn(Future.successful(()))
      when(mockAlertEnvironmentHandlerRepository.insert(any[Seq[AlertEnvironmentHandler]])).thenReturn(Future.successful(()))
      when(mockAlertHashRepository.update(eqTo("Test2"))).thenReturn(Future.successful(()))


      val result: Future[Unit] = Await.ready(alertConfigSchedulerService.updateConfigs(), Duration(20, "seconds"))

      verify(mockAlertEnvironmentHandlerRepository, times(1)).deleteAll()
      verify(mockAlertEnvironmentHandlerRepository, times(1)).insert(any[Seq[AlertEnvironmentHandler]])
      verify(mockAlertHashRepository, times(1)).update(eqTo("Test2"))

      result.value.get.isSuccess shouldBe true

    }

    "return successful when Artifactory has no updated Hash" in new Setup {


      when(mockArtifactoryConnector.getLatestHash()).thenReturn(Future.successful(Some("Test3")))
      when(mockAlertHashRepository.findOne()).thenReturn(Future.successful(Some(LastHash("Test3"))))


      val result: Future[Unit] = Await.ready(alertConfigSchedulerService.updateConfigs(), Duration(20, "seconds"))


      result.value.get.isSuccess shouldBe true
    }
  }

  "AlertConfigSchedulerService.processZip" should {
    "produce a SensuConfig that contains app name and handler name" in {

      val fis = new FileInputStream("./test/resources/happy-output.zip")
      val file = AlertConfigSchedulerService.processZip(fis)

      file.alertConfigs.exists(_.app == "accessibility-statement-frontend.public.mdtp") shouldBe true
      file.alertConfigs.exists(_.app == "add-taxes-frontend.public.mdtp") shouldBe true

      file.productionHandler.get("yta") shouldBe Some(Handler("/etc/sensu/handlers/hmrc_pagerduty_multiteam_env_apiv2.rb --team yta -e aws_production"))
      file.productionHandler.get("platform-ui") shouldBe Some(Handler("/etc/sensu/handlers/hmrc_pagerduty_multiteam_env_apiv2.rb --team platform-ui -e aws_production"))

    }

    "produce a SensuConfig that contains app name and None, when handler does not exist" in {

      val fis = new FileInputStream("./test/resources/missing-handler-output.zip")
      val file = AlertConfigSchedulerService.processZip(fis)

      file.alertConfigs.exists(_.app == "accessibility-statement-frontend.public.mdtp") shouldBe true
      file.alertConfigs.exists(_.app == "add-taxes-frontend.public.mdtp") shouldBe true

      file.productionHandler.get("yta") shouldBe None
      file.productionHandler.get("platform-ui") shouldBe Some(Handler("/etc/sensu/handlers/hmrc_pagerduty_multiteam_env_apiv2.rb --team platform-ui -e aws_production"))

    }
  }

  "AlertConfigSchedulerService.processSensuConfig" should {
    "produce an AlertEnvironmentHandler for a service that has alert config enabled" in {

      val sensuConfig = SensuConfig(Seq(AlertConfig("test.public.mdtp",
        Seq("TEST"))),
        Map("TEST" -> Handler("test/command")))

      AlertConfigSchedulerService.processSensuConfig(sensuConfig) shouldBe List(AlertEnvironmentHandler("test", production = true))

    }

    "produce an AlertEnvironmentHandler for a service that has Alert Config Disabled" in {

      val sensuConfig = SensuConfig(Seq(AlertConfig("test.public.mdtp",
        Seq("TEST"))),
        Map("TEST" -> Handler("test/noop.rb")))
      AlertConfigSchedulerService.processSensuConfig(sensuConfig) shouldBe List(AlertEnvironmentHandler("test", production = false))

    }

    "produce an AlertEnvironmentHandler when a service has No Matching Handler Found in Production" in {

      val sensuConfig = SensuConfig(Seq(AlertConfig("test.public.mdtp", Seq("TEST"))))
      AlertConfigSchedulerService.processSensuConfig(sensuConfig) shouldBe List(AlertEnvironmentHandler("test", production = false))

    }

    "produce an AlertEnvironmentHandler for a service that has No Handler Name Defined in Config" in {

      val sensuConfig = SensuConfig(Seq(AlertConfig("test.public.mdtp",
        Seq())),
        Map("TEST" -> Handler("test/command")))
      AlertConfigSchedulerService.processSensuConfig(sensuConfig) shouldBe List(AlertEnvironmentHandler("test", production = false))

    }

    "produce an empty list when there is No Existing Alert Config" in {

      val sensuConfig = SensuConfig(Seq(), Map("TEST" -> Handler("test/command")))
      AlertConfigSchedulerService.processSensuConfig(sensuConfig) shouldBe List.empty

    }
  }

  trait Setup {

    lazy val mockAlertHashRepository: AlertHashStringRepository = mock[AlertHashStringRepository]
    lazy val mockAlertEnvironmentHandlerRepository: AlertEnvironmentHandlerRepository = mock[AlertEnvironmentHandlerRepository]
    lazy val mockArtifactoryConnector: ArtifactoryConnector = mock[ArtifactoryConnector]

    val alertConfigSchedulerService = new AlertConfigSchedulerService(mockAlertEnvironmentHandlerRepository, mockAlertHashRepository, mockArtifactoryConnector)
  }
}
