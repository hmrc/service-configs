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
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.serviceconfigs.connector._
import uk.gov.hmrc.serviceconfigs.model.BobbyRule
import uk.gov.hmrc.serviceconfigs.persistence.BobbyWarningsNotificationsRepository

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

import uk.gov.hmrc.serviceconfigs.util.DateAndTimeOps._

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


  val futureDatedRuleWindow  = configuration.get[Duration]("bobby-warnings-notifier-service.rule-notification-window")
  val endWindow = LocalDate.now().toInstant.plus(futureDatedRuleWindow.toDays, ChronoUnit.DAYS)
  val lastRunPeriod = configuration.get[Duration]("bobby-warnings-notifier-service.last-run-period")
  lazy val testTeam = configuration.getOptional[String]("bobby-warnings-notifier-service.test-team")


  def sendNotificationsForFutureDatedBobbyViolations(runDate: Instant): Future[Unit] = {
      runNotificationsIfInWindow(runDate) {
        for {
          futureDatedRules         <- bobbyRulesService.findAllRules().map(_.libraries.filter { rule =>
                                        rule.from.toInstant.isAfter(runDate) && rule.from.toInstant.isBefore(endWindow)
                                      })
          _                        = logger.info(s"There are ${futureDatedRules.size} future dated Bobby rules becoming active in the next [${futureDatedRuleWindow}] to send slack notifications for.")
          rulesWithAffectedServices     <- futureDatedRules.foldLeftM{List.empty[(Team, (Service,BobbyRule))]}{ (acc, rule) =>
                                           serviceDependencies.getAffectedServices(group = rule.organisation, artefact = rule.name, versionRange = rule.range)
                                             .map(sds => acc ++ sds.flatMap(sd => sd.teams.map(team => (team, (sd.service, rule)))))
                                         }
          grouped: List[(Team, List[(Service, BobbyRule)])]
                                  = rulesWithAffectedServices.groupMap(_._1)(_._2).toList
          slackResponses           <- grouped.foldLeftM{List.empty[(Team,SlackNotificationResponse)]}{
                                        case (acc,(team, drs)) =>
                                          val message = MessageDetails(
                                            text = s"Hello ${team.teamName}, please be aware that the following builds will fail after *${endWindow.toString}* because of new Bobby Rules:"
                                          , attachments =
                                              drs.map(dr => Attachment(s"`${dr._1.serviceName}` affected by ${dr._2.organisation}.${dr._2.name} ${dr._2.range} - see " +
                                               url"https://catalogue.tax.service.gov.uk/repositories/${dr._1.serviceName}#environmentTabs"))
                                          )
                                          slackNotificationsConnector.sendMessage(SlackNotificationRequest(GithubTeam(testTeam.getOrElse(team.teamName)), message)).map(resp => acc :+ (team, resp))
                                       }
          _                         <- reportOnSlackResponses(slackResponses)
          _                         <- bobbyWarningsRepository.setLastRunDate(runDate.truncatedTo(ChronoUnit.DAYS))
        } yield logger.info("Completed sending Slack messages for Bobby Warnings")
    }
  }

  private def reportOnSlackResponses(slackResponses: List[(Team, SlackNotificationResponse)]) =
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

  private def runNotificationsIfInWindow(now: Instant)(f: => Future[Unit]): Future[Unit] =
      now.maybeWorkingHours match {
        case Some(workingHours) =>
            bobbyWarningsRepository.getLastWarningsDate().flatMap {
              case Some(lrd) if lrd.isAfter(workingHours.truncatedTo(ChronoUnit.DAYS).minus(lastRunPeriod.toDays, ChronoUnit.DAYS)) =>
                logger.info(s"Not running Bobby Warning Notifications. Last run date was $lrd")
                Future.unit
              case optLrd =>
                logger.info(optLrd.fold(s"Running Bobby Warning Notifications for the first time")(d => s"Running Bobby Warnings Notifications. Last ran $d"))
                f
            }
        case _ =>
          logger.info(s"Not running Bobby Warnings Notifications out of hours")
          Future.unit
      }

}
