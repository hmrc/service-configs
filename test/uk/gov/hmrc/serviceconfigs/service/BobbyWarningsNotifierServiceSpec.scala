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

import org.mockito.MockitoSugar.{mock, when}
import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.connector._
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, BobbyRules, ServiceName, TeamName}
import uk.gov.hmrc.serviceconfigs.persistence.{BobbyWarningsNotificationsRepository, ServiceRelationshipRepository}

import java.time.temporal.ChronoUnit.DAYS
import java.time.{LocalDateTime, ZoneOffset}
import java.util.concurrent.TimeUnit
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.Duration


class BobbyWarningsNotifierServiceSpec
  extends AnyWordSpec
  with Matchers
  with ScalaFutures
  with IntegrationPatience
  with MockitoSugar {


  "BobbyWarningsNotifierService.sendNotifications" should {

    "do nothing if out of hours" in new Setup{
      val outOfHoursInstant = LocalDateTime.of(2023, 9, 22, 22, 0, 0).toInstant(ZoneOffset.UTC)
      underTest.sendNotifications(outOfHoursInstant)
      verifyZeroInteractions(mockBobbyRulesService, mockServiceDependenciesConnector, mockBobbyWarningsRepository, mockSlackNotificationsConnector)
    }

    "do nothing if the service has already been run in the notifications period" in new Setup {
      when(mockBobbyWarningsRepository.getLastWarningsRunTime()).thenReturn(Future.successful(Some(yesterday)))

      underTest.sendNotifications(nowAsInstant).futureValue
      verifyZeroInteractions(mockBobbyRulesService, mockServiceDependenciesConnector, mockSlackNotificationsConnector)
    }

    "do nothing if the service has already been run today" in new Setup {
      when(mockBobbyWarningsRepository.getLastWarningsRunTime()).thenReturn(Future.successful(Some(nowAsInstant)))

      underTest.sendNotifications(nowAsInstant).futureValue
      verifyZeroInteractions(mockBobbyRulesService, mockServiceDependenciesConnector, mockSlackNotificationsConnector)
    }

    "do nothing if the service is exempt and no end of life repositories" in new Setup {
      // Bobby notification mocks
      when(mockBobbyWarningsRepository.getLastWarningsRunTime()).thenReturn(Future.successful(None))
      when(mockBobbyRulesService.findAllRules()).thenReturn(Future.successful(bobbyRules))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation, "exampleLib"       , range)).thenReturn(Future.successful(Seq(sd3)))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation, "exampleLib2"      , range)).thenReturn(Future.successful(Nil))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation, "anotherExampleLib", range)).thenReturn(Future.successful(Nil))
      // End of life notification mocks
      when(mockTeamsAndRepositoriesConnector.getRepos()).thenReturn(Future.successful(Seq(Repo("Test", Seq.empty, None))))

      when(mockBobbyWarningsRepository.setLastRunTime(nowAsInstant)).thenReturn(Future.unit)

      underTest.sendNotifications(nowAsInstant).futureValue
      verifyZeroInteractions(mockSlackNotificationsConnector)
    }

    "send end of life notifications to relevant teams" in new Setup {

      when(mockBobbyWarningsRepository.getLastWarningsRunTime()).thenReturn(Future.successful(None))
      when(mockBobbyRulesService.findAllRules()).thenReturn(Future.successful(BobbyRules(Seq.empty, Seq.empty)))

      val expectedSlackNotification1 = SlackNotificationRequest.downstreamMarkedForDecommissioning(GithubTeam("team-1"), eolRepo.name, nowAsInstant, Seq(repo2.name))
      val expectedSlackNotification2 = SlackNotificationRequest.downstreamMarkedForDecommissioning(GithubTeam("team-2"), eolRepo.name, nowAsInstant, Seq(repo2.name))
      val expectedSlackNotification3 = SlackNotificationRequest.downstreamMarkedForDecommissioning(GithubTeam("team-3"), eolRepo.name, nowAsInstant, Seq(repo3.name))

      when(mockTeamsAndRepositoriesConnector.getRepos()).thenReturn(Future.successful(Seq(eolRepo, repo2, repo3, repo4)))
      when(mockServiceRelationshipRepository.getInboundServices(ServiceName(eolRepo.name))).thenReturn(Future.successful(Seq(ServiceName(repo2.name), ServiceName(repo3.name))))
      when(mockSlackNotificationsConnector.sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])).thenReturn(Future.successful(SlackNotificationResponse(List.empty)))

      when(mockBobbyWarningsRepository.setLastRunTime(nowAsInstant)).thenReturn(Future.unit)

      underTest.sendNotifications(nowAsInstant).futureValue
      verify(mockSlackNotificationsConnector, times(1)).sendMessage(eqTo(expectedSlackNotification1))(any[HeaderCarrier])
      verify(mockSlackNotificationsConnector, times(1)).sendMessage(eqTo(expectedSlackNotification2))(any[HeaderCarrier])
      verify(mockSlackNotificationsConnector, times(1)).sendMessage(eqTo(expectedSlackNotification3))(any[HeaderCarrier])
    }

    "send end of life notifications to relevant teams and group repos belong to same team" in new Setup {

      when(mockBobbyWarningsRepository.getLastWarningsRunTime()).thenReturn(Future.successful(None))
      when(mockBobbyRulesService.findAllRules()).thenReturn(Future.successful(BobbyRules(Seq.empty, Seq.empty)))

      val expectedSlackNotification1 = SlackNotificationRequest.downstreamMarkedForDecommissioning(GithubTeam("team-1"), eolRepo.name, nowAsInstant, Seq(repo2.name, repo4.name))
      val expectedSlackNotification2 = SlackNotificationRequest.downstreamMarkedForDecommissioning(GithubTeam("team-2"), eolRepo.name, nowAsInstant, Seq(repo2.name))
      val expectedSlackNotification3 = SlackNotificationRequest.downstreamMarkedForDecommissioning(GithubTeam("team-3"), eolRepo.name, nowAsInstant, Seq(repo3.name))

      when(mockTeamsAndRepositoriesConnector.getRepos()).thenReturn(Future.successful(Seq(eolRepo, repo2, repo3, repo4)))
      when(mockServiceRelationshipRepository.getInboundServices(ServiceName(eolRepo.name))).thenReturn(Future.successful(Seq(ServiceName(repo2.name), ServiceName(repo3.name), ServiceName(repo4.name))))
      when(mockSlackNotificationsConnector.sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])).thenReturn(Future.successful(SlackNotificationResponse(List.empty)))

      when(mockBobbyWarningsRepository.setLastRunTime(nowAsInstant)).thenReturn(Future.unit)

      underTest.sendNotifications(nowAsInstant).futureValue
      verify(mockSlackNotificationsConnector, times(1)).sendMessage(eqTo(expectedSlackNotification1))(any[HeaderCarrier])
      verify(mockSlackNotificationsConnector, times(1)).sendMessage(eqTo(expectedSlackNotification2))(any[HeaderCarrier])
      verify(mockSlackNotificationsConnector, times(1)).sendMessage(eqTo(expectedSlackNotification3))(any[HeaderCarrier])
    }

    "run for the first time" in new Setup {
      when(mockConfiguration.getOptional[String]("bobby-warnings-notifier-service.test-team")).thenReturn(None)
      when(mockBobbyWarningsRepository.getLastWarningsRunTime()).thenReturn(Future.successful(None))
      when(mockBobbyRulesService.findAllRules()).thenReturn(Future.successful(bobbyRules))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation, "exampleLib"       , range)).thenReturn(Future.successful(Seq(sd1, sd2)))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation, "exampleLib2"      , range)).thenReturn(Future.successful(Seq(sd2)))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation, "anotherExampleLib", range)).thenReturn(Future.successful(Nil))
      when(mockSlackNotificationsConnector.sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])).thenReturn(Future.successful(SlackNotificationResponse(List.empty)))
      when(mockTeamsAndRepositoriesConnector.getRepos()).thenReturn(Future.successful(Seq.empty))

      when(mockBobbyWarningsRepository.setLastRunTime(nowAsInstant)).thenReturn(Future.unit)

      underTest.sendNotifications(nowAsInstant).futureValue
      verify(mockSlackNotificationsConnector, times(2)).sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])
    }

    "be run if the last time the service was run before the notifications period" in new Setup {
      // Bobby notification mocks
      when(mockConfiguration.getOptional[String]("bobby-warnings-notifier-service.test-team")).thenReturn(None)
      when(mockBobbyWarningsRepository.getLastWarningsRunTime()).thenReturn(Future.successful(Some(eightDays)))
      when(mockBobbyRulesService.findAllRules()).thenReturn(Future.successful(bobbyRules))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation,"exampleLib"        , range)).thenReturn(Future.successful(Seq(sd1, sd2)))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation,"exampleLib2"       , range)).thenReturn(Future.successful(Seq(sd2)))
      when(mockServiceDependenciesConnector.getAffectedServices(organisation, "anotherExampleLib", range)).thenReturn(Future.successful(Nil))
      when(mockSlackNotificationsConnector.sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])).thenReturn(Future.successful(SlackNotificationResponse(List.empty)))
      when(mockBobbyWarningsRepository.setLastRunTime(nowAsInstant)).thenReturn(Future.unit)
      when(mockTeamsAndRepositoriesConnector.getRepos()).thenReturn(Future.successful(Seq.empty))

      underTest.sendNotifications(nowAsInstant).futureValue
      verify(mockSlackNotificationsConnector, times(2)).sendMessage(any[SlackNotificationRequest])(any[HeaderCarrier])
    }
  }
}



trait Setup  {

  val hc = HeaderCarrier()
  val team1 = TeamName("Team1")
  val team2 = TeamName("Team2")

  val now = LocalDateTime.of(2023, 9, 22, 9, 0, 0)
  val nowAsInstant = now.toInstant(ZoneOffset.UTC)

  val sd1 = AffectedService(serviceName = ServiceName("some-service")       , teamNames = List(team1, team2))
  val sd2 = AffectedService(serviceName = ServiceName("some-other-service") , teamNames = List(team1))
  val sd3 = AffectedService(serviceName = ServiceName("some-exempt-service"), teamNames = List(team1))

  val eolRepo = Repo("repo1", Seq("team-1"),           Some(nowAsInstant))
  val repo2   = Repo("repo2", Seq("team-1", "team-2"), None)
  val repo3   = Repo("repo3", Seq("team-3"),           None)
  val repo4   = Repo("repo4", Seq("team-1"),           None)

  val organisation = "uk.gov.hmrc"
  val range = "[0.0.0,)"

  val eightDays   = now.minus(8L, DAYS).toInstant(ZoneOffset.UTC)
  val yesterday   = now.minus(1L, DAYS).toInstant(ZoneOffset.UTC)
  val oneWeek     = now.plusWeeks(1L).toLocalDate
  val threeMonths = now.plusMonths(3L).toLocalDate

  val futureDatedBobbyRule1Week       = BobbyRule(organisation = organisation, name = "exampleLib",        range = range, reason = "Bad Practice", from = oneWeek    , exemptProjects = Seq(sd3.serviceName.asString))
  val secondFutureDatedBobbyRule1Week = BobbyRule(organisation = organisation, name = "exampleLib2",       range = range, reason = "Bad Practice", from = oneWeek    , exemptProjects = Seq.empty)
  val futureDatedRule3Months          = BobbyRule(organisation = organisation, name = "anotherExampleLib", range = range, reason = "Bad Practice", from = threeMonths, exemptProjects = Seq.empty)

  val bobbyRules = BobbyRules(libraries = Seq(futureDatedBobbyRule1Week, secondFutureDatedBobbyRule1Week, futureDatedRule3Months), plugins = Seq.empty)

  val mockBobbyRulesService              = mock[BobbyRulesService]
  val mockServiceDependenciesConnector   = mock[ServiceDependenciesConnector]
  val mockBobbyWarningsRepository        = mock[BobbyWarningsNotificationsRepository]
  val mockSlackNotificationsConnector    = mock[SlackNotificationsConnector]
  val mockTeamsAndRepositoriesConnector  = mock[TeamsAndRepositoriesConnector]
  val mockServiceRelationshipRepository  = mock[ServiceRelationshipRepository]

  val mockConfiguration = mock[Configuration]

  when(mockConfiguration.get[Duration]("bobby-warnings-notifier-service.rule-notification-window")).thenReturn(Duration(30, TimeUnit.DAYS))
  when(mockConfiguration.get[Duration]("bobby-warnings-notifier-service.last-run-period")).thenReturn(Duration(7, TimeUnit.DAYS))

  val underTest = new BobbyWarningsNotifierService(mockBobbyRulesService, mockServiceDependenciesConnector, mockBobbyWarningsRepository, mockSlackNotificationsConnector, mockServiceRelationshipRepository, mockTeamsAndRepositoriesConnector,  mockConfiguration)
}


