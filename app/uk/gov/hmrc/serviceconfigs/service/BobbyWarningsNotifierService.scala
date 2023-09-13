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

import cats.implicits._
import play.api.{Configuration, Logging}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector._
import uk.gov.hmrc.serviceconfigs.model.BobbyRule
import uk.gov.hmrc.serviceconfigs.persistence.BobbyWarningsRepository

import java.time.LocalDate
import java.time.temporal.{ChronoUnit, TemporalAmount}
import java.time.temporal.ChronoUnit.{MONTHS, WEEKS}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyWarningsNotifierService @Inject()(
  bobbyRulesService          : BobbyRulesService,
  serviceDependencies        : ServiceDependenciesConnector,
  bobbyWarningsRepository    : BobbyWarningsRepository,
  slackNotificationsConnector: SlackNotificationsConnector,
  configuration : Configuration
)(implicit
  ec: ExecutionContext
) extends Logging {

  implicit val hc = HeaderCarrier()


  val futureDatedRuleWindow: TemporalAmount = configuration.get[TemporalAmount]("bobby-warnings-notifier-service.rule-notification-window")
  val endWindow = LocalDate.now().plus(futureDatedRuleWindow)
  val lastRunPeriod = configuration.get[TemporalAmount]("bobby-warnings-notifier-service.last-run-period")

  def sendNotificationsForFutureDatedBobbyViolations: Future[Unit] =
      runNotificationsIfInWindow {
        for {
          futureDatedRules         <- bobbyRulesService.findAllRules().map(_.libraries.filter { rule =>
                                        rule.from.isAfter(LocalDate.now()) && rule.from.isBefore(endWindow)
                                      })
          _                        = logger.info(s"There are ${futureDatedRules.size} future dated Bobby rules becoming active in the next [${futureDatedRuleWindow}] to send slack notifications for.")
          rulesWithTeams           <- futureDatedRules.foldLeftM{List.empty[(String,BobbyRule)]}{ (acc,rule) =>
                                           serviceDependencies.getDependentTeams(group = rule.organisation, artefact = rule.name, versionRange = rule.range)
                                             .map(teams => acc ++ teams.map(t => (t, rule)))
                                         }
          grouped                  = rulesWithTeams.groupMap(_._1)(_._2)
          _                        = grouped.map { case (team, rules) =>
                                          val message = MessageDetails(s"Please be aware your team has Bobby Rule Warnings that will become failures after ${endWindow.toString}", "username", "emoji",
                                            attachments = rules.map(r => Attachment(s"${r.organisation}.${r.name} will fail  with: ${r.reason} on and after the: ${r.from}")), showAttachmentAuthor = true)
                                          slackNotificationsConnector.sendMessage(SlackNotificationRequest(ChannelLookup.GithubTeam(team), message))
                                       }
                                   //todo what sort of checking do we need here on the Slack Responses?
          -                        <- bobbyWarningsRepository.updateLastWarningDate()
        } yield logger.info("Completed sending Slack messages for Bobby Warnings")

  }


  private def runNotificationsIfInWindow(f: => Future[Unit]): Future[Unit] =
    bobbyWarningsRepository.getLastWarningsDate().flatMap {
      lastRunDate =>
        if (lastRunDate.isBefore(LocalDate.now().minus(lastRunPeriod))) {
          logger.info(s"Running Bobby Warning Notifications. Last run date was ${lastRunDate.toString}")
          f
        } else {
          logger.debug(s"Not running Bobby Warning Notifications as they were run on ${lastRunDate.toString}")
          Future.unit
        }
    }
}
