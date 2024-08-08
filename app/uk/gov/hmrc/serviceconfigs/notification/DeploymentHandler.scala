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

package uk.gov.hmrc.serviceconfigs.notification

import org.apache.pekko.actor.ActorSystem
import cats.Applicative
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.ReleasesApiConnector
import uk.gov.hmrc.serviceconfigs.model.{CommitId, Environment, FileName, RepoName, ServiceName, Version}
import uk.gov.hmrc.serviceconfigs.service.SlugInfoService

import java.time.Instant
import scala.collection.immutable.TreeMap
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentHandler @Inject()(
  configuration  : Configuration,
  slugInfoService: SlugInfoService
)(using
  actorSystem    : ActorSystem,
  ec             : ExecutionContext
) extends SqsConsumer(
  name           = "Deployment"
, config         = SqsConfig("aws.sqs.deployment", configuration)
):
  import DeploymentHandler._

  private given HeaderCarrier = HeaderCarrier()

  override protected def processMessage(message: Message): Future[MessageAction] =
    logger.info(s"Starting processing Deployment message with ID '${message.messageId()}'")
    val et = for
      payload <- EitherT.fromEither[Future](
                   Json
                     .parse(message.body)
                     .validate(mdtpEventReads)
                     .asEither
                     .left
                     .map(error => s"Could not parse message with ID '${message.messageId()}'.  Reason: " + error.toString)
                 )
        _     <- (payload.eventType, payload.optEnvironment) match
                   case ("deployment-complete", Some(environment)) =>
                     EitherT.liftF[Future, String, Boolean](
                       slugInfoService.updateDeployment(
                         env         = environment,
                         serviceName = payload.serviceName,
                         deployment  = ReleasesApiConnector.Deployment(
                                         serviceName    = payload.serviceName
                                       , optEnvironment = Some(environment)
                                       , version        = payload.version
                                       , lastDeployed   = payload.time
                                       , deploymentId   = payload.deploymentId
                                       , config         = payload.config
                                       )
                       )
                     ).map: requiresUpdate =>
                       if requiresUpdate then
                         logger.info(s"Deployment ${payload.serviceName} ${payload.version} $environment has been processed")
                       else
                         logger.info(s"Deployment ${payload.serviceName} ${payload.version} $environment has already been processed (redeployment without config changes)")
                   case (_, None) =>
                     logger.info(s"Not processing message '${message.messageId()}' with unrecognised environment")
                     EitherT.pure[Future, String](())
                   case (eventType, _) =>
                     logger.info(s"Not processing message '${message.messageId()}' with event_type $eventType")
                     EitherT.pure[Future, String](())
    yield
      logger.info(s"Deployment message with ID '${message.messageId()}' successfully processed.")
      MessageAction.Delete(message)

    et.value.map:
      case Left(error)   => logger.error(error)
                            MessageAction.Ignore(message)
      case Right(action) => action

object DeploymentHandler:
  case class DeploymentEvent(
    eventType     : String
  , optEnvironment: Option[Environment]
  , serviceName   : ServiceName
  , version       : Version
  , deploymentId  : String
  , config        : Seq[ReleasesApiConnector.DeploymentConfigFile]
  , time          : Instant
  )

  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Reads, JsObject, __}

   private given Applicative[Reads] =
    new Applicative[Reads]:
      override def pure[A](a: A): Reads[A] =
        Reads.pure(a)

      override def ap[A, B](ff: Reads[A => B])(fa: Reads[A]): Reads[B] =
        for
          f <- ff
          a <- fa
        yield f(a)

  private val ConfigKey = ".*\\.(\\d+)\\.(\\w+)".r

  lazy val mdtpEventReads: Reads[DeploymentEvent] =
    summon[Reads[JsObject]]
      .flatMap: jsObject =>
        for
          config <- TreeMap(
                      jsObject.fields
                        .collect:
                          case (ConfigKey(i, k), v) => (i.toInt, k, v.as[String])
                        .groupBy(_._1)
                        .toSeq: _*
                      )
                      .toList
                      .traverse:
                        case (i, s) =>
                          for
                            repoName <- s.collectFirst { case (_, k, v) if k == "repoName" => Reads.pure(RepoName(v)) }.getOrElse(Reads.failed(s"config.$i missing repoName"))
                            fileName <- s.collectFirst { case (_, k, v) if k == "fileName" => Reads.pure(FileName(v)) }.getOrElse(Reads.failed(s"config.$i missing fileName"))
                            commitId <- s.collectFirst { case (_, k, v) if k == "gitSha"   => Reads.pure(CommitId(v)) }.getOrElse(Reads.failed(s"config.$i missing gitSha"))
                          yield ReleasesApiConnector.DeploymentConfigFile(repoName = repoName, fileName = fileName, commitId = commitId)
          res    <- deploymentEventReads1.map(_.copy(config = config))
        yield res

  private lazy val deploymentEventReads1: Reads[DeploymentEvent] =
    given Reads[Option[Environment]] = __.read[String].map(Environment.parse)

    ( (__ \ "event_type"          ).read[String]
    ~ (__ \ "environment"         ).read[Option[Environment]]
    ~ (__ \ "microservice"        ).read[ServiceName](ServiceName.format)
    ~ (__ \ "microservice_version").read[Version](Version.format)
    ~ (__ \ "deployment_id"       ).read[String]
    ~ Reads.pure(Seq.empty[ReleasesApiConnector.DeploymentConfigFile]) // config - to be added
    ~ (__ \ "event_date_time"     ).read[Instant]
    )(DeploymentEvent.apply _)
