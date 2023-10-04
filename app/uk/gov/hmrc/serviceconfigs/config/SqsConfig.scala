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

package uk.gov.hmrc.serviceconfigs.config

import java.net.URL
import javax.inject.{Inject, Singleton}
import play.api.Configuration

import uk.gov.hmrc.http.StringContextOps

trait SqsConfig {
  def queueUrl           : URL
  def maxNumberOfMessages: Int
  def waitTimeSeconds    : Int
}

@Singleton
class CommonSqsConfig @Inject()(configuration: Configuration) {
  lazy val urlPrefix          : URL    = new URL(configuration.get[String]("artefact.receiver.aws.sqs.queue-pr"))
  lazy val deploymentQueue    : String = configuration.get[String]("artefact.receiver.aws.sqs.queue-deployment")
  lazy val slugQueue          : String = configuration.get[String]("artefact.receiver.aws.sqs.queue-slug")
  lazy val maxNumberOfMessages: Int    = configuration.get[Int]("artefact.receiver.aws.sqs.maxNumberOfMessages")
  lazy val waitTimeSeconds    : Int    = configuration.get[Int]("artefact.receiver.aws.sqs.waitTimeSeconds")
}

@Singleton
class DeploymentSqsConfig @Inject()(sqs: CommonSqsConfig) extends SqsConfig {
  override lazy val queueUrl           : URL = url"${sqs.urlPrefix}/${sqs.deploymentQueue}"
  override lazy val maxNumberOfMessages: Int = sqs.maxNumberOfMessages
  override lazy val waitTimeSeconds    : Int = sqs.waitTimeSeconds
}

@Singleton
class DeploymentDeadLetterSqsConfig @Inject()(sqs: CommonSqsConfig) extends SqsConfig {
  override lazy val queueUrl           : URL = url"${sqs.urlPrefix}/${sqs.deploymentQueue}-deadletter"
  override lazy val maxNumberOfMessages: Int = sqs.maxNumberOfMessages
  override lazy val waitTimeSeconds    : Int = sqs.waitTimeSeconds
}

@Singleton
class SlugSqsConfig @Inject()(sqs: CommonSqsConfig) extends SqsConfig {
  override lazy val queueUrl           : URL = url"${sqs.urlPrefix}/${sqs.slugQueue}"
  override lazy val maxNumberOfMessages: Int = sqs.maxNumberOfMessages
  override lazy val waitTimeSeconds    : Int = sqs.waitTimeSeconds
}

@Singleton
class SlugDeadLetterSqsConfig @Inject()(sqs: CommonSqsConfig) extends SqsConfig {
  override lazy val queueUrl           : URL = url"${sqs.urlPrefix}/${sqs.slugQueue}-deadletter"
  override lazy val maxNumberOfMessages: Int = sqs.maxNumberOfMessages
  override lazy val waitTimeSeconds    : Int = sqs.waitTimeSeconds
}
