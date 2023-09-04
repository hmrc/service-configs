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

import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{Attachment, ChannelLookup, MessageDetails, ServiceDependenciesConnector, SlackNotificationRequest, SlackNotificationsConnector}

import java.time.LocalDate
import java.time.temporal.ChronoUnit.MONTHS
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.flatMap._
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, BobbyRules}

class BobbyWarningsNotfierService @Inject() (
  bobbyRulesService          : BobbyRulesService,
  serviceDependencies        : ServiceDependenciesConnector,
  slackNotificationsConnector: SlackNotificationsConnector
)(implicit
  ec: ExecutionContext
) extends Logging {

  implicit val hc = HeaderCarrier()
  //todo make this a value in config
  val endWindow = LocalDate.now().plus(2L, MONTHS)

  def sendNotificationsForFutureDatedBobbyViolations() = {

    for {
      _                <- Future.successful(logger.info("Starting the sending of slack warnings for future dated rules"))
      futureDatedRules <- bobbyRulesService.findAllRules().map(_.libraries.filter { rule =>
                               rule.from.isAfter(LocalDate.now()) && rule.from.isBefore(endWindow)
                             })
      _                <- Future.successful(logger.info(s"There are ${futureDatedRules.size} rules for teams to be notified of"))
      rulesWithTeams   <- Future.sequence{
                             futureDatedRules.map{rule =>
                               serviceDependencies.getDependentTeams(group = rule.organisation, artefact = rule.name, versionRange = rule.range)
                                 .map(teams => teams.map(t => (t,rule)))
                             }
                           }
      grouped          <- Future.successful(rulesWithTeams.flatten.groupMap(_._1)(_._2))
      _                <- Future.sequence {
                            grouped.map { case (team, rules) =>  //todo is this the same team as the slack channel name or do we need to check with UMP
                              val message = MessageDetails(s"Please be aware your team has Bobby Rule Warnings that will become failures after ${endWindow.toString}", "username", "emoji",
                                attachments = rules.map(r => Attachment(s"${r.organisation}.${r.name} will fail  with: ${r.reason} on and after the: ${r.from}")), showAttachmentAuthor = true)
                            slackNotificationsConnector.sendMessage(SlackNotificationRequest(ChannelLookup.SlackChannel(List(team)), message))
                           }
                         }
    } yield ()
  }

}
