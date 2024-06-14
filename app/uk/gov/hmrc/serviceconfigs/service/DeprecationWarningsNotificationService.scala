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
import uk.gov.hmrc.serviceconfigs.persistence.{DeprecationWarningsNotificationRepository, ServiceRelationshipRepository, SlackNotificationsRepository}
import uk.gov.hmrc.serviceconfigs.util.DateAndTimeOps._

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate}
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeprecationWarningsNotificationService @Inject()(
  bobbyRulesService                         : BobbyRulesService,
  serviceDependencies                       : ServiceDependenciesConnector,
  deprecationWarningsNotificationRepository : DeprecationWarningsNotificationRepository,
  slackNotificationsConnector               : SlackNotificationsConnector,
  serviceRelationshipsRepository            : ServiceRelationshipRepository,
  teamsAndRepositoriesConnector             : TeamsAndRepositoriesConnector,
  configuration : Configuration
)(using
  ec: ExecutionContext
) extends Logging {

  private given HeaderCarrier = HeaderCarrier()

  private val futureDatedRuleWindow  = configuration.get[Duration]("deprecation-warnings-notification-service.rule-notification-window")
  private val endWindow              = LocalDate.now().toInstant.plus(futureDatedRuleWindow.toDays, ChronoUnit.DAYS)
  private val lastRunPeriod          = configuration.get[Duration]("deprecation-warnings-notification-service.last-run-period")
  private lazy val testTeam          = configuration.getOptional[String]("deprecation-warnings-notification-service.test-team")

  private val bobbyNotificationEnabled     = configuration.get[Boolean]("deprecation-warnings-notification-service.bobby-notification-enabled")
  private val endOfLifeNotificationEnabled = configuration.get[Boolean]("deprecation-warnings-notification-service.end-of-life-notification-enabled")

  def sendNotifications(runTime: Instant): Future[Unit] = {
    runNotificationsIfInWindow(runTime) {
      for {
        _ <- bobbyNotifications(runTime)
        _ <- deprecatedNotifications()
        _ <- deprecationWarningsNotificationRepository.setLastRunTime(runTime)
      } yield logger.info("Completed sending deprecation warning messages")
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

  private def deprecatedNotifications()(using hc: HeaderCarrier, ec: ExecutionContext): Future[Unit] = {
    if (endOfLifeNotificationEnabled) {
      for {
        repositories          <- teamsAndRepositoriesConnector.getRepos()
        eolRepositories       = repositories.filter(_.isDeprecated)
        serviceRelationships  <- eolRepositories.foldLeftM(Seq.empty[(TeamsAndRepositoriesConnector.Repo, Seq[ServiceName])]) {
          case (acc, eolRepo) =>
            serviceRelationshipsRepository.getInboundServices(ServiceName(eolRepo.repoName.asString)).map {
              inboundServices => acc :+ (eolRepo, inboundServices)
            }
        }
        responses              <- serviceRelationships.foldLeftM(Seq.empty[SlackNotificationResponse]) {
            case (acc, (repo, relationships)) =>
              val enrichRelationships   = repositories.filter(repo => relationships.map(_.asString).contains(repo.repoName.asString))
              val teamNames             = enrichRelationships.flatMap(_.teamNames).distinct
              val groupReposByTeamNames = teamNames.map(name => name -> enrichRelationships.filter(_.teamNames.contains(name)))

              groupReposByTeamNames.foldLeftM(Seq.empty[SlackNotificationResponse]) {
                case (acc, (teamName, repos)) =>
                  val msg = SlackNotificationRequest.downstreamMarkedAsDeprecated(GithubTeam(teamName), repo.repoName, repo.endOfLifeDate, repos.map(_.repoName))
                  slackNotificationsConnector.sendMessage(msg).map(acc :+ _)
              }.map(acc ++ _)
          }
      } yield logger.info(s"Completed sending ${responses.length} Slack messages for deprecated repositories")
    } else {
      logger.info("Deprecated repositories Slack notifications disabled")
      Future.unit
    }
  }

  private def runNotificationsIfInWindow(now: Instant)(f: => Future[Unit]): Future[Unit] =
    if (isInWorkingHours(now)) {
      deprecationWarningsNotificationRepository.getLastWarningsRunTime().flatMap {
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
