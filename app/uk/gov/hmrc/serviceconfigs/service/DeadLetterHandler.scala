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
import akka.stream.alpakka.sqs.MessageAction.Delete
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import com.google.inject.Inject
import play.api.Logger
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.serviceconfigs.config.ArtefactReceivingConfig

import scala.concurrent.ExecutionContext
import scala.util.Try

class DeadLetterHandler @Inject()
(config: ArtefactReceivingConfig)
(implicit val actorSystem: ActorSystem,
 implicit val materializer: Materializer,
 implicit val executionContext: ExecutionContext) {

  val logger = Logger("application.DeadLetterHandler")

  if (!config.isEnabled) {
    logger.debug("DeadLetterHandler is disabled.")
  }

  private lazy val queueUrl = config.sqsSlugDeadLetterQueue
  private lazy val settings = SqsSourceSettings()

  private lazy val awsSqsClient = Try({
    val client = SqsAsyncClient.builder()
      .httpClient(AkkaHttpClient.builder().withActorSystem(actorSystem).build())
      .build()
    actorSystem.registerOnTermination(client.close())
    client
  }).recover {
    case e: Throwable => logger.error(e.getMessage, e); throw e
  }.get

  if (config.isEnabled) {
    SqsSource(
      queueUrl,
      settings)(awsSqsClient)
      .map(logMessage)
      .runWith(SqsAckSink(queueUrl)(awsSqsClient)).recover {
      case e: Throwable => logger.error(e.getMessage, e); throw e
    }
  }

  private def logMessage(message: Message) = {
    logger.warn(
      s"""Dead letter message with
         |ID: '${message.messageId()}'
         |Body: '${message.body()}'""".stripMargin)
    Delete(message)
  }
}
