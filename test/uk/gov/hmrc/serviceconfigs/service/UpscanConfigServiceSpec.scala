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
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.connector.{ConfigAsCodeConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, UpscanConfig}
import uk.gov.hmrc.serviceconfigs.persistence.UpscanConfigRepository

import java.io.FileInputStream
import java.util.zip.ZipInputStream
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class UpscanConfigServiceSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience {

  "UpscanConfigService.update" should {
    "return the expecter services per environment" in new UpscanCofigServiceFixture {
      val expectedUpscanConfigs = Seq(
        UpscanConfig(ServiceName("service1"), "https://github.com/hmrc/upscan-app-config/blob/main/qa/verify.yaml#L10", Environment.QA),
        UpscanConfig(ServiceName("service2"), "https://github.com/hmrc/upscan-app-config/blob/main/qa/verify.yaml#L12", Environment.QA),
        UpscanConfig(ServiceName("service3"), "https://github.com/hmrc/upscan-app-config/blob/main/qa/verify.yaml#L14", Environment.QA),
        UpscanConfig(ServiceName("service1"), "https://github.com/hmrc/upscan-app-config/blob/main/production/verify.yaml#L10", Environment.Production),
        UpscanConfig(ServiceName("service2"), "https://github.com/hmrc/upscan-app-config/blob/main/production/verify.yaml#L12", Environment.Production),
      )

      service.update().futureValue

      verify(mockUpscanConfigRepository, times(1)).putAll(expectedUpscanConfigs)
    }
  }

  private abstract class UpscanCofigServiceFixture(
    repoNames: Seq[String] = Seq("service1", "service2", "service3")
  ) {
    val mockUpscanConfigRepository        = mock[UpscanConfigRepository]
    val mockConfigAsCodeConnector         = mock[ConfigAsCodeConnector]
    val mockTeamsAndRepositoriesConnector = mock[TeamsAndRepositoriesConnector]

    when(mockTeamsAndRepositoriesConnector.getRepos(
      archived    = None,
      repoType    = Some("Service"),
      teamName    = None,
      serviceType = None,
      tags        = Nil,
    )).thenReturn(Future.successful(repoNames.map(rn => TeamsAndRepositoriesConnector.Repo(rn))))

    when(mockConfigAsCodeConnector.streamUpscanAppConfig())
      .thenReturn(
        Future.successful(
          new ZipInputStream(
            new FileInputStream("./test/resources/upscan-app-config.zip")
          )
      )
    )

    when(mockUpscanConfigRepository.putAll(any[Seq[UpscanConfig]]))
      .thenReturn(Future.unit)

    val service = new UpscanConfigService(
      mockUpscanConfigRepository,
      mockConfigAsCodeConnector,
      mockTeamsAndRepositoriesConnector,
    )
  }
}
