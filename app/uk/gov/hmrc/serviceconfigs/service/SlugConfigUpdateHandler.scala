/*
 * Copyright 2019 HM Revenue & Customs
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

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.alpakka.sqs.MessageAction.{Delete, Ignore}
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logger
import play.api.libs.json.Json
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.serviceconfigs.config.ArtefactReceivingConfig
import uk.gov.hmrc.serviceconfigs.model.{ApiSlugInfoFormats, SlugMessage}

import scala.concurrent.{ExecutionContext, Future}

class SlugConfigUpdateHandler @Inject()
(config: ArtefactReceivingConfig, slugConfigurationService: SlugConfigurationService)
(implicit val actorSystem: ActorSystem,
 implicit val materializer: Materializer,
 implicit val executionContext: ExecutionContext) {

  if(!config.isEnabled) {
    Logger.debug("SlugConfigUpdateHandler is disabled.")
  }

  private lazy val queueUrl = config.sqsSlugQueue
  private lazy val settings = SqsSourceSettings()
  private lazy val awsSqsClient = {
    val client = SqsAsyncClient.builder()
      .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
      .build()

    actorSystem.registerOnTermination(client.close())
    client
  }

  if(config.isEnabled) {
    SqsSource(
      queueUrl,
      settings)(awsSqsClient)
      .map(logMessage)
      .map(messageTo)
      .mapAsync(10)(save)
      .map(acknowledge)
      .runWith(SqsAckSink(queueUrl)(awsSqsClient))
  }

  private def logMessage(message: Message): Message = {
    Logger.debug(s"Starting processing message with ID '${message.messageId()}'")
    message
  }

  private def messageTo(message: Message): (Message, Either[String, SlugMessage]) =
    (message,
      Json.parse(message.body())
        .validate(ApiSlugInfoFormats.slugFormat)
        .asEither.left.map(error => s"Could not message with ID '${message.messageId()}'.  Reason: " + error.toString()))

  private def save(input: (Message, Either[String, SlugMessage])): Future[(Message, Either[String, Unit])] = {
    val (message, eitherSlugInfo) = input

    (eitherSlugInfo match {
      case Left(error) => Future(Left(error))
      case Right(slugMessage) =>
        for {
          si <- slugConfigurationService.addSlugInfo(slugMessage.info)
            .map(saveResult => if (saveResult) Right(()) else Left(s"SlugInfo for message (ID '${message.messageId()}') was sent on but not saved."))
            .recover {
              case e =>
                val errorMessage = s"Could not store slug info for message with ID '${message.messageId()}'"
                Logger.error(errorMessage, e)
                Left(s"$errorMessage ${e.getMessage}")
            }
          dc <- slugConfigurationService.addDependencyConfigurations(slugMessage.configs)
            .map(saveResult => if (saveResult.forall(_ == true)) Right(()) else Left(s"Configuration for message (ID '${message.messageId()}') was sent on but not saved."))
            .recover {
              case e =>
                val errorMessage = s"Could not store slug configuration for message with ID '${message.messageId()}'"
                Logger.error(errorMessage, e)
                Left(s"$errorMessage ${e.getMessage}")
            }
        } yield (si, dc) match {
          case (Left(siMsg), Left(dcMsg)) => Left(s"Slug Info message: $siMsg${sys.props("line.separator")}Dependency Config message$dcMsg")
          case (Right(_), Left(dcMsg)) => Left(dcMsg)
          case (Left(siMsg), Right(_)) => Left(siMsg)
          case (Right(_), Right(_)) => Right(())
        }
    }).map((message, _))
  }

  private def acknowledge(input: (Message, Either[String, Unit])) = {
    val (message, eitherResult) = input
    eitherResult match {
      case Left(error) =>
        Logger.error(error)
        Ignore(message)
      case Right(_) =>
        Logger.debug(s"Message with ID '${message.messageId()}' successfully processed.")
        Delete(message)
    }
  }
}
