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

import akka.actor.ActorSystem
import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{Attachment, ChannelLookup, MessageDetails, ServiceDependenciesConnector, SlackNotificationRequest, SlackNotificationsConnector, UserManagementConnector}

import java.time.LocalDate
import java.time.temporal.ChronoUnit.{MONTHS, WEEKS}
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.flatMap._
import play.api.inject.ApplicationLifecycle
import uk.gov.hmrc.mongo.TimestampSupport
import uk.gov.hmrc.serviceconfigs.config.SchedulerConfigs
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, BobbyRules}
import uk.gov.hmrc.serviceconfigs.persistence.BobbyWarningsRepository
import uk.gov.hmrc.serviceconfigs.scheduler.{MongoLockRepository, SchedulerUtils, TimePeriodLockService}

import scala.concurrent.duration.DurationInt

class BobbyWarningsNotifierService @Inject()(
  bobbyRulesService          : BobbyRulesService,
  serviceDependencies        : ServiceDependenciesConnector,
  bobbyWarningsRepository    : BobbyWarningsRepository,
  slackNotificationsConnector: SlackNotificationsConnector,
  userManagementConnector    : UserManagementConnector
)(implicit
  ec: ExecutionContext
) extends Logging {

  implicit val hc = HeaderCarrier()


  //todo make this a value in config
  val endWindow = LocalDate.now().plus(2L, MONTHS)


  def sendNotificationsForFutureDatedBobbyViolations: Future[Unit] = {
      runNotificationsIfInWindow(bobbyWarningsRepository.getLastWarningsDate()) {
        for {
          futureDatedRules         <- bobbyRulesService.findAllRules().map(_.libraries.filter { rule =>
                                        rule.from.isAfter(LocalDate.now()) && rule.from.isBefore(endWindow)
                                      })
          _                        <- Future.successful(logger.info(s"There are ${futureDatedRules.size} future dated rules becoming active"))
          rulesWithTeams           <- Future.sequence {
                                         futureDatedRules.map { rule =>
                                           serviceDependencies.getDependentTeams(group = rule.organisation, artefact = rule.name, versionRange = rule.range)
                                             .map(teams => teams.map(t => (t, rule)))
                                         }
                                       }
          grouped                  <- Future.successful(rulesWithTeams.flatten.groupMap(_._1)(_._2))
          groupedWithSlackChannel  <- Future.sequence {
                                        grouped.map { case (team, rules) =>
                                           userManagementConnector.getSlackChannelByTeam(team).map((_,rules))
                                        }
                                      }
          _                        <- Future.sequence {
                                        groupedWithSlackChannel.map { case (team, rules) =>
                                          val message = MessageDetails(s"Please be aware your team has Bobby Rule Warnings that will become failures after ${endWindow.toString}", "username", "emoji",
                                            attachments = rules.map(r => Attachment(s"${r.organisation}.${r.name} will fail  with: ${r.reason} on and after the: ${r.from}")), showAttachmentAuthor = true)
                                          slackNotificationsConnector.sendMessage(SlackNotificationRequest(ChannelLookup.SlackChannel(List(team)), message))
                                       }
                                     }
                                   //todo what sort of checking do we need here on the Slack Responses?
          -                        <- bobbyWarningsRepository.updateLastWarningDate()
        } yield logger.info("Completed sending Slack messages for Bobby Warnings")
      }
  }


  private def runNotificationsIfInWindow(lastRunDate: Future[LocalDate])(f: => Future[Unit]): Future[Unit] =
    lastRunDate.flatMap {
      date =>
        if (date.isBefore(LocalDate.now().minus(1L, WEEKS))) {
          f
        } else {
          logger.info(s"Not running Bobby Warning Notifications as they were run on ${date.toString}")
          Future.unit
        }
    }
}
