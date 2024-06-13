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

import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.mockito.MockitoSugar
import scala.concurrent.ExecutionContext.Implicits.global

import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.model.Environment._
import uk.gov.hmrc.serviceconfigs.persistence.{LastHashRepository, OutagePageRepository}
import java.util.zip.ZipInputStream

class OutagePageServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with MockitoSugar {

  "OutagePageService.extractOutagePages" should {
    "extract all the outage-pages from the gihub zipball" in new OutagePageServiceFixture {
      val expectedOutagePages = List(
        OutagePage(
          ServiceName("example-service"),
          List(Development, Production, QA)
        )
      )

      val outagePages = service.extractOutagePages(zipStream)

      outagePages should contain theSameElementsAs expectedOutagePages
    }
  }

  private class OutagePageServiceFixture(zipball: String = "/outage-pages.zip") {
    val configAsCodeConnector = mock[ConfigAsCodeConnector]
    val lastHashRepository    = mock[LastHashRepository]
    val outagePageRepository  = mock[OutagePageRepository]

    val zipStream = new ZipInputStream(getClass.getResource(zipball).openStream())

    val service = new OutagePageService(
        configAsCodeConnector,
        lastHashRepository,
        outagePageRepository,
    )
  }
}
