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
import cats.data.EitherT
import cats.implicits._
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.ArtefactProcessorConnector
import uk.gov.hmrc.serviceconfigs.model.ServiceName
import uk.gov.hmrc.serviceconfigs.service.SlugConfigurationService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugHandler @Inject()(
  configuration             : Configuration,
  slugConfigurationService  : SlugConfigurationService,
  artefactProcessorConnector: ArtefactProcessorConnector
)(using
  actorSystem               : ActorSystem,
  ec                        : ExecutionContext
) extends SqsConsumer(
  name                      = "SlugInfo"
, config                    = SqsConfig("aws.sqs.slug", configuration)
):

  private given HeaderCarrier = HeaderCarrier()

  override protected def processMessage(message: Message): Future[MessageAction] =
    logger.info(s"Starting processing SlugInfo message with ID '${message.messageId()}'")
    (for
       payload <- EitherT.fromEither[Future](
                    Json
                      .parse(message.body)
                      .validate(MessagePayload.reads)
                      .asEither
                      .left
                      .map(error => s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                  )
       action  <- payload match
                    case available: MessagePayload.JobAvailable =>
                      for
                        _                 <- EitherT.cond[Future](available.jobType == "slug", (), s"${available.jobType} was not 'slug'")
                        slugInfo          <- EitherT.fromOptionF(
                                               artefactProcessorConnector.getSlugInfo(available.name, available.version),
                                               s"SlugInfo for name: ${available.name}, version: ${available.version} was not found"
                                             )
                        dependencyConfigs <- EitherT.fromOptionF(
                                               artefactProcessorConnector.getDependencyConfigs(available.name, available.version),
                                               s"DependencyConfigs for name: ${available.name}, version: ${available.version} was not found"
                                             )
                        _                 <- EitherT[Future, String, Unit]:
                                               slugConfigurationService.addSlugInfo(slugInfo)
                                               .map(Right.apply)
                                               .recover:
                                                 case e =>
                                                   val errorMessage = s"Could not store SlugInfo for message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version})"
                                                   logger.error(errorMessage, e)
                                                   Left(s"$errorMessage ${e.getMessage}")
                        _                 <- EitherT[Future, String, Unit]:
                                               slugConfigurationService
                                                 .addDependencyConfigurations(dependencyConfigs)
                                                 .map(Right.apply)
                                                 .recover:
                                                   case e =>
                                                     val errorMessage = s"Could not store DependencyConfigs for message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version})"
                                                     logger.error(errorMessage, e)
                                                     Left(s"$errorMessage ${e.getMessage}")
                      yield
                        logger.info(s"SlugInfo message with ID '${message.messageId()}' (${slugInfo.name} ${slugInfo.version}) successfully processed.")
                        MessageAction.Delete(message)
                    case deleted: MessagePayload.JobDeleted =>
                      for
                        _ <- EitherT.cond[Future](deleted.jobType == "slug", (), s"${deleted.jobType} was not 'slug'")
                        _ <- EitherT(
                               slugConfigurationService.deleteSlugInfo(ServiceName(deleted.name), deleted.version)
                                 .map(Right.apply)
                                 .recover:
                                   case e =>
                                     val errorMessage = s"Could not delete SlugInfo for message with ID '${message.messageId()}' (${deleted.name} ${deleted.version})"
                                     logger.error(errorMessage, e)
                                     Left(s"$errorMessage ${e.getMessage}")
                             )
                      yield
                        logger.info(s"SlugInfo deleted message with ID '${message.messageId()}' (${deleted.name} ${deleted.version}) successfully processed.")
                        MessageAction.Delete(message)
     yield action
    ).value.map:
      case Left(error) =>
        logger.error(error)
        MessageAction.Ignore(message)
      case Right(action) =>
        action
