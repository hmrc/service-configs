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
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import cats.data.EitherT
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logger
import play.api.libs.json.Json
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.serviceconfigs.config.ArtefactReceivingConfig
import uk.gov.hmrc.serviceconfigs.model.{ApiSlugInfoFormats, SlugMessage}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import scala.util.control.NonFatal

class SlugConfigUpdateHandler @Inject()(
  messageHandling: SqsMessageHandling,
  slugConfigurationService: SlugConfigurationService,
  config: ArtefactReceivingConfig)(
  implicit val actorSystem: ActorSystem,
  materializer: Materializer,
  executionContext: ExecutionContext) {

  private val logger = Logger(getClass)

  if (!config.isEnabled) {
    logger.warn("SlugConfigUpdateHandler is disabled.")
  }

  private lazy val queueUrl = config.sqsSlugQueue
  private lazy val settings = SqsSourceSettings()

  private lazy val awsCredentialsProvider: AwsCredentialsProvider =
    Try(DefaultCredentialsProvider.builder().build()).recover {
      case NonFatal(e) => logger.error(e.getMessage, e); throw e
    }.get

  private lazy val awsSqsClient = Try({
    val client = SqsAsyncClient
      .builder()
      .credentialsProvider(awsCredentialsProvider)
      .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
      .build()

    actorSystem.registerOnTermination(client.close())
    client
  }).recover {
    case NonFatal(e) => logger.error(e.getMessage, e); throw e
  }.get

  if (config.isEnabled) {
    SqsSource(queueUrl, settings)(awsSqsClient)
      .map(logMessage)
      .mapAsync(10)(processMessage)
      .runWith(SqsAckSink(queueUrl)(awsSqsClient))
      .recover {
        case NonFatal(e) => logger.error(e.getMessage, e); throw e
      }
  }

  private def logMessage(message: Message): Message = {
    logger.info(s"Starting processing message with ID '${message.messageId()}'")
    message
  }

  private def processMessage(message: Message): Future[MessageAction] =
    for {
      parsed <- messageHandling
                 .decompress(message.body)
                 .map(
                   decompressed =>
                     Json
                       .parse(decompressed)
                       .validate(ApiSlugInfoFormats.slugFormat)
                       .asEither
                       .left
                       .map { error =>
                         logger.error(
                           s"Could not parse message with ID '${message.messageId}'.  Reason: " + error.toString)
                         Ignore(message)
                     })
                 .recover {
                   case NonFatal(e) =>
                     logger.error(s"Could not decompress message with ID '${message.messageId}'", e)
                     Left(Ignore(message))
                 }
      saved <- processSlugMessage(message, parsed)
    } yield saved

  private def processSlugMessage(message: Message, parsed: Either[Ignore, SlugMessage]): Future[MessageAction] = {
    import cats.implicits._

    val response: EitherT[Future, Ignore, Delete] = for {
      slugMessage <- EitherT.fromEither[Future](parsed)
      si <- EitherT[Future, Ignore, Delete](
             slugConfigurationService
               .addSlugInfo(slugMessage.info)
               .map(_ => Right(Delete(message)))
               .recover {
                 case e =>
                   logger.error(s"Could not store slug info for message with ID '${message.messageId}'", e)
                   Left[Ignore, Delete](Ignore(message))
               })
      dc <- EitherT[Future, Ignore, Delete](
             slugConfigurationService
               .addDependencyConfigurations(slugMessage.configs)
               .map(_ => Right(Delete(message)))
               .recover {
                 case e =>
                   logger.error(s"Could not store slug configuration for message with ID '${message.messageId}'", e)
                   Left[Ignore, Delete](Ignore(message))
               })
      _ = logger.info(s"Message with ID '${message.messageId}' successfully processed.")

    } yield dc
    response.value.map(_.merge)
  }

}
