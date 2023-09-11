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

import org.mockito.MockitoSugar.mock
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{LdapTeam, ServiceDependenciesConnector, SlackNotificationRequest, SlackNotificationResponse, SlackNotificationsConnector, UserManagementConnector}
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, BobbyRules}
import uk.gov.hmrc.serviceconfigs.persistence.BobbyWarningsRepository

import java.time.LocalDate
import java.time.temporal.ChronoUnit.{DAYS, MONTHS, WEEKS}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class BobbyWarningsNotfierServiceSpec
  extends AnyWordSpec
  with Matchers
  with ScalaFutures
  with IntegrationPatience
  with MockitoSugar {


  "The BobbyWarningsNotifierService" should {
    "do nothing if the service has already been run in the notifications period " in new Setup {
      when(mockBobbyWarningsRepository.getLastWarningsDate()).thenReturn(Future.successful(yesterday))

      underTest.sendNotificationsForFutureDatedBobbyViolations.futureValue

      verifyZeroInteractions(mockBobbyRulesService, mockServiceDependenciesConnector, mockSlackNotificationsConnector)
    }
    "be run if the last time the service was run before the notifications period" in new Setup {
      when(mockBobbyWarningsRepository.getLastWarningsDate()).thenReturn(Future.successful(eightDays))
      when(mockBobbyRulesService.findAllRules()).thenReturn(Future.successful(bobbyRules))


      when(mockServiceDependenciesConnector.getDependentTeams(organisation,"exampleLib", range)).thenReturn(Future.successful(Seq(team1, team2)))
      when(mockServiceDependenciesConnector.getDependentTeams(organisation,"exampleLib2", range)).thenReturn(Future.successful(Seq(team1)))

      when(mockUserManagementConnector.getSlackChannelByTeam(team1)).thenReturn(Future.successful("TeamOne"))
      when(mockUserManagementConnector.getSlackChannelByTeam(team2)).thenReturn(Future.successful("TeamTwo"))

      when(mockSlackNotificationsConnector.sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])).thenReturn(Future.successful(SlackNotificationResponse(Seq.empty)))

      when(mockBobbyWarningsRepository.updateLastWarningDate()).thenReturn(Future.unit)

      underTest.sendNotificationsForFutureDatedBobbyViolations.futureValue

      verify(mockSlackNotificationsConnector, times(2)).sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])

    }
  }
}

trait Setup {


  val hc = HeaderCarrier()

  val team1 = "Team1"
  val team2 = "Team2"


  val organisation = "uk.gov.hmrc"
  val range = "[0.0.0,)"
  val futureDatedBobbyRule1Week: BobbyRule = BobbyRule(organisation = organisation, name = "exampleLib", range =range, reason = "Bad Practice", from = LocalDate.now().plus(1L, WEEKS) , exemptProjects = Seq.empty)
  val secondFutureDatedBobbyRule1Week: BobbyRule = BobbyRule(organisation = organisation, name = "exampleLib2", range =range, reason = "Bad Practice", from = LocalDate.now().plus(1L, WEEKS) , exemptProjects = Seq.empty)
  val futureDatedRule3Months: BobbyRule = BobbyRule(organisation = organisation, name = "anotherExampleLib", range = range, reason = "Bad Practice", from = LocalDate.now().plus(3L, MONTHS) , exemptProjects = Seq.empty)

  val bobbyRules = BobbyRules(libraries = Seq(futureDatedBobbyRule1Week, secondFutureDatedBobbyRule1Week, futureDatedRule3Months), plugins = Seq.empty)

  val eightDays = LocalDate.now().minus(8L, DAYS)
  val yesterday = LocalDate.now().minus(1L, DAYS)

  val mockBobbyRulesService = mock[BobbyRulesService]
  val mockServiceDependenciesConnector = mock[ServiceDependenciesConnector]
  val mockBobbyWarningsRepository = mock[BobbyWarningsRepository]
  val mockSlackNotificationsConnector = mock[SlackNotificationsConnector]

  val mockUserManagementConnector: UserManagementConnector = mock[UserManagementConnector]

  val underTest = new BobbyWarningsNotifierService(mockBobbyRulesService, mockServiceDependenciesConnector, mockBobbyWarningsRepository, mockSlackNotificationsConnector, mockUserManagementConnector)
}

