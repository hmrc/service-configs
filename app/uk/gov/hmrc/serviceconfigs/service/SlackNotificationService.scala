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
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, ServiceName, TeamName}
import uk.gov.hmrc.serviceconfigs.persistence.{ServiceRelationshipRepository, SlackNotificationsRepository}
import uk.gov.hmrc.serviceconfigs.util.DateAndTimeOps._

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlackNotificationService @Inject()(
  bobbyRulesService             : BobbyRulesService,
  serviceDependencies           : ServiceDependenciesConnector,
  slackNotificationsRepository  : SlackNotificationsRepository,
  slackNotificationsConnector   : SlackNotificationsConnector,
  serviceRelationshipsRepository: ServiceRelationshipRepository,
  teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector,
  configuration : Configuration
)(implicit
  ec: ExecutionContext
) extends Logging {

  implicit val hc: HeaderCarrier = HeaderCarrier()

  private val futureDatedRuleWindow  = configuration.get[Duration]("slack-notification-service.rule-notification-window")
  private val endWindow              = LocalDate.now().toInstant.plus(futureDatedRuleWindow.toDays, ChronoUnit.DAYS)
  private val lastRunPeriod          = configuration.get[Duration]("slack-notification-service.last-run-period")
  private lazy val testTeam          = configuration.getOptional[String]("slack-notification-service.test-team")

  private val bobbyNotificationEnabled     = configuration.get[Boolean]("slack-notification-service.bobby-notification-enabled")
  private val endOfLifeNotificationEnabled = configuration.get[Boolean]("slack-notification-service.end-of-life-notification-enabled")

  def sendNotifications(runTime: Instant): Future[Unit] = {
    runNotificationsIfInWindow(runTime) {
      for {
        _ <- bobbyNotifications(runTime)
        _ <- endOfLifeNotifications()
        _ <- slackNotificationsRepository.setLastRunTime(runTime)
      } yield logger.info("Completed sending Slack warning messages")
    }
  }

  private def bobbyNotifications(runTime: Instant): Future[Unit] = {
    if (bobbyNotificationEnabled) {
      for {
        futureDatedRules          <- bobbyRulesService
                                       .findAllRules()
                                       .map(_.libraries.filter(rule => rule.from.toInstant.isAfter(runTime) && rule.from.toInstant.isBefore(endWindow)))
        _                         =  logger.info(s"There are ${futureDatedRules.size} future dated Bobby rules becoming active in the next [$futureDatedRuleWindow] to send slack notifications for.")
        rulesWithAffectedServices <- futureDatedRules.foldLeftM(List.empty[(TeamName, (ServiceName, BobbyRule))])( (acc, rule) =>
                                        serviceDependencies
                                          .getAffectedServices(group = rule.organisation, artefact = rule.name, versionRange = rule.range)
                                          .map(_.filterNot(x => rule.exemptProjects.contains(x.serviceName.asString)))
                                          .map(sds => acc ++ sds.flatMap(sd => sd.teamNames.map(team => (team, (sd.serviceName, rule)))))
                                     )
        grouped                   =  rulesWithAffectedServices.groupMap(_._1)(_._2).toList
        slackResponses            <- grouped.foldLeftM(List.empty[(TeamName, SlackNotificationResponse)]) { case (acc, (teamName, drs)) =>
                                         val channelLookup = GithubTeam(testTeam.getOrElse(teamName.asString))
                                         val request       = SlackNotificationRequest.bobbyWarning(channelLookup, teamName, drs)
                                         slackNotificationsConnector.sendMessage(request).map(resp => acc :+ (teamName, resp))
                                     }
        _                         =  slackResponses.map {
                                       case (teamName, rsp) if rsp.errors.nonEmpty => logger.warn(s"Sending Bobby Warning message to ${teamName.asString} had errors ${rsp.errors.mkString(" : ")}")
                                       case (teamName, _)                          => logger.info(s"Successfully sent Bobby Warning message to ${teamName.asString}")
                                     }
      } yield logger.info("Completed sending Slack messages for Bobby Warnings")
    } else {
      logger.info("Bobby slack notifications disabled")
      Future.unit
    }
  }

  private def endOfLifeNotifications()(implicit hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] = {
    if (endOfLifeNotificationEnabled) {
      teamsAndRepositoriesConnector.getRepos().flatMap {
        repositories =>
          val eolRepos = repositories.filter(_.endOfLifeDate.isDefined)
          Future.sequence(eolRepos.map {
            repo =>
              val serviceName = ServiceName(repo.name)
              serviceRelationshipsRepository.getInboundServices(ServiceName(repo.name)).flatMap {
                case getInboundServices if getInboundServices.nonEmpty =>

                  val enrichServiceRelationships = repositories.filter(repo => getInboundServices.map(_.asString).contains(repo.name))
                  val teamNames                  = enrichServiceRelationships.flatMap(_.teamNames).distinct
                  val groupReposByTeamNames      = teamNames.map(name => name -> enrichServiceRelationships.filter(_.teamNames.contains(name)))

                  Future.sequence(groupReposByTeamNames.map {
                    case (teamName, repos) =>
                      val msg = SlackNotificationRequest.downstreamMarkedForDecommissioning(GithubTeam(teamName), serviceName.asString, repo.endOfLifeDate.get, repos.map(_.name))
                      slackNotificationsConnector.sendMessage(msg)
                  })
                case _ => Future.successful(Seq.empty)
              }
          }
          )
      }.map(_ => logger.info("Completed sending Slack messages for end of life repositories"))
    } else {
      logger.info("End of Life Slack notifications disabled")
      Future.unit
    }
  }


  private def runNotificationsIfInWindow(now: Instant)(f: => Future[Unit]): Future[Unit] =
    if (isInWorkingHours(now)) {
      slackNotificationsRepository.getLastWarningsRunTime().flatMap {
        case Some(lrd) if lrd.isAfter(now.truncatedTo(ChronoUnit.DAYS).minus(lastRunPeriod.toDays, ChronoUnit.DAYS)) =>
          logger.info(s"Not running Slack Notifications. Last run date was $lrd")
          Future.unit
        case optLrd =>
          logger.info(optLrd.fold(s"Running Slack Notifications for the first time")(d => s"Running Slack Notifications. Last run date was $d"))
          f
      }
    } else {
      logger.info(s"Not running Slack Notifications. Out of hours")
      Future.unit
    }

}
