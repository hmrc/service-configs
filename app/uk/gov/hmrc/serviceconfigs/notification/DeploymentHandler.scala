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
import uk.gov.hmrc.serviceconfigs.config.ArtefactReceivingConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

@Singleton
class DeploymentHandler @Inject()(
  config: ArtefactReceivingConfig,
)(implicit
  actorSystem : ActorSystem,
  materializer: Materializer,
  ec          : ExecutionContext
) extends Logging {

  private lazy val queueUrl = config.sqsDeploymentQueue
  private lazy val settings = SqsSourceSettings()

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
        case NonFatal(e) => logger.error(s"Failed to process sqs messages: ${e.getMessage}", e); Supervision.Restart
      })
      .runWith(SqsAckSink(queueUrl.toString)(awsSqsClient))
  else
    logger.warn("DeploymentHandler is disabled.")

  private def processMessage(message: Message): Future[MessageAction] = {
    logger.info(s"Starting processing Deployment message with ID '${message.messageId()}'")
    (for {
       payload <- EitherT.fromEither[Future](
                    Json.parse(message.body)
                      .validate(MessagePayload.reads)
                      .asEither.left.map(error => s"Could not parse message with ID '${message.messageId()}'.  Reason: " + error.toString)
                  )
       _       =  logger.info(s"Deployment message with ID '${message.messageId()}' successfully processed.")
       action  =  MessageAction.Delete(message)
     } yield action
    ).value.map {
      case Left(error)   => logger.error(error)
                            MessageAction.Ignore(message)
      case Right(action) => action
    }
  }
}
