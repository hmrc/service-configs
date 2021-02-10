/*
 * Copyright 2021 HM Revenue & Customs
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
import akka.stream.alpakka.sqs.MessageAction.Delete
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logging
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.serviceconfigs.config.ArtefactReceivingConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

class DeadLetterHandler @Inject()
( messageHandling: SqsMessageHandling,
  config         : ArtefactReceivingConfig
) (implicit
   actorSystem : ActorSystem,
   materializer: Materializer,
   ec          : ExecutionContext
) extends Logging {

  if (!config.isEnabled) {
    logger.debug("DeadLetterHandler is disabled.")
  }

  private lazy val queueUrl = config.sqsSlugDeadLetterQueue
  private lazy val settings = SqsSourceSettings()

  private lazy val awsSqsClient =
    Try{
      val client = SqsAsyncClient.builder()
        .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
        .build()
      actorSystem.registerOnTermination(client.close())
      client
    }.recoverWith { case NonFatal(e) => logger.error(e.getMessage, e); Failure(e) }
     .get

  if (config.isEnabled) {
    SqsSource(
      queueUrl.toString(),
      settings)(awsSqsClient)
      .mapAsync(10)(processMessage)
      .runWith(SqsAckSink(queueUrl.toString())(awsSqsClient))
      .recoverWith { case NonFatal(e) => logger.error(e.getMessage, e); Future.failed(e) }
  }

  private def processMessage(message: Message) = {
    messageHandling.decompress(message.body).onComplete {
      case Success(m) =>
        logger.warn(
          s"""Dead letter message with
             |ID: '${message.messageId}'
             |Body: '$m'""".stripMargin)
      case Failure(e) =>
        // if the decompress failed, log without message body
        logger.error(s"Could not decompress message with ID '${message.messageId}'", e)
    }
    Future(Delete(message))
  }
}
