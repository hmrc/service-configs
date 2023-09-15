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
import uk.gov.hmrc.serviceconfigs.persistence.BobbyWarningsNotificationsRepository

import java.time.LocalDate
import java.time.temporal.TemporalAmount
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyWarningsNotifierService @Inject()(
  bobbyRulesService          : BobbyRulesService,
  serviceDependencies        : ServiceDependenciesConnector,
  bobbyWarningsRepository    : BobbyWarningsNotificationsRepository,
  slackNotificationsConnector: SlackNotificationsConnector,
  configuration : Configuration
)(implicit
  ec: ExecutionContext
) extends Logging {

  implicit val hc = HeaderCarrier()


  val futureDatedRuleWindow: TemporalAmount = configuration.get[TemporalAmount]("bobby-warnings-notifier-service.rule-notification-window")
  val endWindow = LocalDate.now().plus(futureDatedRuleWindow)
  val lastRunPeriod = configuration.get[TemporalAmount]("bobby-warnings-notifier-service.last-run-period")
  val slackIcon = configuration.get[String]("bobby-warnings-notifier-service.slack-icon")
  lazy val testTeam = configuration.getOptional[String]("bobby-warnings-notifier-service.test-team")


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
          slackResponses           <- grouped.toList.foldLeftM{List.empty[(String,SlackNotificationResponse)]}{ case (acc,(team, rules)) =>
                                          val message = MessageDetails(s"Please be aware your team has Bobby Rule Warnings that will become failures after ${endWindow.toString}", "username", slackIcon,
                                            attachments = rules.map(r => Attachment(s"${r.organisation}.${r.name} will fail  with: ${r.reason} on and after the: ${r.from}")), showAttachmentAuthor = true)
                                          slackNotificationsConnector.sendMessage(SlackNotificationRequest(ChannelLookup.GithubTeam(testTeam.getOrElse(team)), message)).map(resp => acc :+ (team, resp))
                                       }
          _                         = reportOnSlackResponses(slackResponses)
          _                         <- bobbyWarningsRepository.updateLastWarningDate()
        } yield logger.info("Completed sending Slack messages for Bobby Warnings")
  }


  private def reportOnSlackResponses(slackResponses: List[(String, SlackNotificationResponse)]) =
    Future.successful {
     slackResponses.map {
       case (team, response) =>
         if (response.errors.nonEmpty) {
           logger.warn(s"Sending Bobby Warning message to $team had errors ${response.errors.mkString(" : ")}")
         } else {
           logger.info(s"Successfully sent Bobby Warning message to ${response.successfullySentTo}")
         }
     }
  }

  private def runNotificationsIfInWindow(f: => Future[Unit]): Future[Unit] =
    bobbyWarningsRepository.getLastWarningsDate().flatMap {
      case Some(lastRunDate) =>
        if (lastRunDate.isBefore(LocalDate.now().minus(lastRunPeriod))) {
          logger.info(s"Running Bobby Warning Notifications. Last run date was ${lastRunDate.toString}")
          f
        } else {
          logger.info(s"Not running Bobby Warning Notifications as they were run on ${lastRunDate.toString}")
          Future.unit
        }
      case _ =>
        logger.info(s"Last Run Date has not been set running for the first time")
        f
    }
}
