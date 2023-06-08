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

import akka.actor.ActorSystem
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import akka.stream.{ActorAttributes, Materializer, Supervision}
import cats.data.EitherT
import cats.implicits._
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.config.ArtefactReceivingConfig
import uk.gov.hmrc.serviceconfigs.connector.ArtefactProcessorConnector
import uk.gov.hmrc.serviceconfigs.model.ServiceName
import uk.gov.hmrc.serviceconfigs.service.SlugConfigurationService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

@Singleton
class SlugConfigUpdateHandler @Inject()(
  slugConfigurationService  : SlugConfigurationService,
  config                    : ArtefactReceivingConfig,
  artefactProcessorConnector: ArtefactProcessorConnector
)(implicit
  actorSystem : ActorSystem,
  materializer: Materializer,
  ec          : ExecutionContext
) extends Logging {

  private lazy val queueUrl = config.sqsSlugQueue
  private lazy val settings = SqsSourceSettings()

  private implicit val hc = HeaderCarrier()

  private lazy val awsSqsClient =
    Try {
      val client = SqsAsyncClient
        .builder()
        .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
        .build()

      actorSystem.registerOnTermination(client.close())
      client
    }.recoverWith {
      case NonFatal(e) => logger.error(s"Failed to set up awsSqsClient: ${e.getMessage}", e); Failure(e)
    }.get

  if (config.isEnabled)
    SqsSource(queueUrl.toString, settings)(awsSqsClient)
      .mapAsync(1)(processMessage)
      .withAttributes(ActorAttributes.supervisionStrategy {
        case t: Throwable => logger.error(s"Failed to process sqs messages: ${t.getMessage}", t); Supervision.Restart
      })
      .runWith(SqsAckSink(queueUrl.toString)(awsSqsClient))
  else
    logger.warn("SlugConfigUpdateHandler is disabled.")

  private def processMessage(message: Message): Future[MessageAction] = {
    logger.info(s"Starting processing SlugInfo message with ID '${message.messageId()}'")
    (for {
       payload <- EitherT.fromEither[Future](
                    Json.parse(message.body)
                      .validate(MessagePayload.reads)
                      .asEither.left.map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                  )
       action  <- payload match {
                    case available: MessagePayload.JobAvailable =>
                      for {
                        _                 <- EitherT.cond[Future](available.jobType == "slug", (), s"${available.jobType} was not 'slug'")
                        slugInfo          <- EitherT.fromOptionF(
                                               artefactProcessorConnector.getSlugInfo(available.name, available.version),
                                               s"SlugInfo for name: ${available.name}, version: ${available.version} was not found"
                                             )
                        _                 =  logger.info(s"${available.name} ${available.version}: slugInfo.dependencies.size=${slugInfo.dependencies.size}")
                        dependencyConfigs <- EitherT.fromOptionF(
                                               artefactProcessorConnector.getDependencyConfigs(available.name, available.version),
                                               s"DependencyConfigs for name: ${available.name}, version: ${available.version} was not found"
                                             )
                        _                 =  logger.info(s"${available.name} ${available.version}: dependencyConfigs.size=${dependencyConfigs.size}")
                        _                 <- EitherT[Future, String, Unit](
                                               slugConfigurationService.addSlugInfo(slugInfo)
                                               .map(Right.apply)
                                               .recover {
                                                 case e =>
                                                   val errorMessage = s"Could not store SlugInfo for message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version})"
                                                   logger.error(errorMessage, e)
                                                   Left(s"$errorMessage ${e.getMessage}")
                                               }
                                             )
                        _                 <- EitherT[Future, String, Unit](
                                               slugConfigurationService
                                                 .addDependencyConfigurations(dependencyConfigs)
                                                 .map(Right.apply)
                                                 .recover {
                                                   case e =>
                                                     val errorMessage = s"Could not store DependencyConfigs for message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version})"
                                                     logger.error(errorMessage, e)
                                                     Left(s"$errorMessage ${e.getMessage}")
                                                 }
                                             )
                      } yield {
                        logger.info(s"SlugInfo message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version}) successfully processed.")
                        MessageAction.Delete(message)
                      }
                    case deleted: MessagePayload.JobDeleted =>
                      for {
                        _ <- EitherT.cond[Future](deleted.jobType == "slug", (), s"${deleted.jobType} was not 'slug'")
                        _ <- EitherT(
                               slugConfigurationService.deleteSlugInfo(ServiceName(deleted.name), deleted.version)
                                 .map(Right.apply)
                                 .recover {
                                   case e =>
                                     val errorMessage = s"Could not delete SlugInfo for message with ID '${message.messageId()}' (${deleted.name} ${deleted.version})"
                                     logger.error(errorMessage, e)
                                     Left(s"$errorMessage ${e.getMessage}")
                                 }
                             )
                       } yield {
                         logger.info(s"SlugInfo deleted message with ID '${message.messageId()}' (${deleted.name} ${deleted.version}) successfully processed.")
                         MessageAction.Delete(message)
                       }
                  }
     } yield action
    ).value.map {
      case Left(error) =>
        logger.error(error)
        MessageAction.Ignore(message)
      case Right(action) =>
        action
    }
  }
}
