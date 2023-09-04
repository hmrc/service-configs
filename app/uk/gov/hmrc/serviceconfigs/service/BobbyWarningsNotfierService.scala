package uk.gov.hmrc.serviceconfigs.service

import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{ChannelLookup, MessageDetails, ServiceDependenciesConnector, SlackNotificationRequest, SlackNotificationsConnector}

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
      bobbyRules       <- bobbyRulesService.findAllRules()
      libraries        <- Future.successful(bobbyRules.libraries)
      futureDatedRules <- Future.successful(libraries.filter { rule =>
                               rule.from.isAfter(LocalDate.now()) && rule.from.isBefore(endWindow)
                             })
      rulesWithTeams   <- Future.sequence{
                             futureDatedRules.map{rule =>
                               serviceDependencies.getDependentTeams(group = rule.organisation, artefact = rule.name, versionRange = rule.range)
                                 .map(teams => teams.map(t => (t,rule)))
                             }
                           }
      grouped          <- Future.successful(rulesWithTeams.flatten.groupMap(_._1)(_._2))

      message   =      MessageDetails("some warning text", "username", "emoji", attachments = Seq.empty, showAttachmentAuthor = false)

      _       <- slackNotificationsConnector.sendMessage(SlackNotificationRequest(ChannelLookup.SlackChannel(List.empty), message))
    } yield ()


  }

}
