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

import org.apache.pekko.{Done, NotUsed}
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source
import play.api.{Configuration, Logging}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, Message, ReceiveMessageRequest}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationLong
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._
import scala.util.control.NonFatal
import java.net.URL

case class SqsConfig(
  keyPrefix    : String,
  configuration: Configuration
):
  lazy val queueUrl           : URL = URL(configuration.get[String](s"$keyPrefix.queueUrl"))
  lazy val maxNumberOfMessages: Int = configuration.get[Int](s"$keyPrefix.maxNumberOfMessages")
  lazy val waitTimeSeconds    : Int = configuration.get[Int](s"$keyPrefix.waitTimeSeconds")

abstract class SqsConsumer(
  name       : String
, config     : SqsConfig
)(using
  actorSystem: ActorSystem,
  ec         : ExecutionContext
) extends Logging:

  private val awsSqsClient: SqsAsyncClient =
    val client = SqsAsyncClient.builder().build()
    actorSystem.registerOnTermination(client.close())
    client

  private def getMessages(req: ReceiveMessageRequest): Future[Seq[Message]] =
    logger.info(s"receiving $name messages")
    awsSqsClient.receiveMessage(req).asScala
     .map(_.messages.asScala.toSeq)
     .map: res =>
       logger.info(s"received $name ${res.size} messages")
       res

  private def deleteMessage(message: Message): Future[Unit] =
    awsSqsClient.deleteMessage(
      DeleteMessageRequest.builder()
        .queueUrl(config.queueUrl.toString)
        .receiptHandle(message.receiptHandle)
        .build()
    ).asScala
    .map(_ => ())

  private def dedupe(source: Source[Message, NotUsed]): Source[Message, NotUsed] =
    Source
      .single(Message.builder.messageId("----------").build) // dummy value since the dedupe will ignore the first entry
      .concat(source)
      .sliding(2, 1)
      .mapConcat {
        case prev +: current +: _
          if (prev.messageId == current.messageId) =>
            logger.warn(s"Read the same $name message ID twice ${prev.messageId} - ignoring duplicate")
            List.empty
        case prev +: current +: _ =>
          List(current)
      }

  def runQueue(): Future[Done] =
    dedupe(
      Source
        .repeat:
          ReceiveMessageRequest.builder()
            .queueUrl(config.queueUrl.toString)
            .maxNumberOfMessages(config.maxNumberOfMessages)
            .waitTimeSeconds(config.waitTimeSeconds)
            .build()
        .mapAsync(parallelism = 1)(getMessages)
        .mapConcat(xs => xs)
    )
    .mapAsync(parallelism = 1) { message =>
      processMessage(message)
        .flatMap:
          case MessageAction.Delete(message) => deleteMessage(message)
          case MessageAction.Ignore(_)       => Future.unit
        .recover:
          case NonFatal(e) => logger.error(s"Failed to process $name messages", e)
    }
    .run()
    .andThen: res =>
      logger.warn(s"Queue $name terminated: $res - restarting")
      actorSystem.scheduler.scheduleOnce(10.seconds)(runQueue())

  runQueue()

  protected def processMessage(message: Message): Future[MessageAction]

enum MessageAction:
  case Delete(message: Message)
  case Ignore(message: Message)
