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
import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.persistence.{LastHashRepository, LatestConfigRepository}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AppConfigServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with MockitoSugar {

  private val mockLatestConfigRepo = mock[LatestConfigRepository]
  private val mockLastHashRepo     = mock[LastHashRepository]
  private val mockConfigConnector  = mock[ConfigAsCodeConnector]

  "appConfigCommonYaml" should {
    "catch and convert serviceType with api- prefix" in {
      when(mockLatestConfigRepo.find(any[String], any[String]))
        .thenReturn(Future.successful(Some("config")))

      val service = new AppConfigService(mockLatestConfigRepo, mockLastHashRepo, mockConfigConnector)

      service.appConfigCommonYaml(Environment.QA, "api-microservice").futureValue

      verify(mockLatestConfigRepo, times(1)).find("app-config-common", "qa-microservice-common.yaml")
    }
  }

}
