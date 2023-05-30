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

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.http.StringContextOps
import java.net.URL

@Singleton
class ArtefactReceivingConfig @Inject()(
  configuration: Configuration,
  serviceConfig: ServicesConfig
) {
  private lazy val sqsQueueUrlPrefix =
    new URL(configuration.get[String]("artefact.receiver.aws.sqs.queue-prefix"))

  private lazy val sqsQueueSlugInfo =
    configuration.get[String]("artefact.receiver.aws.sqs.queue-slug")

  lazy val sqsSlugQueue          : URL = url"$sqsQueueUrlPrefix/$sqsQueueSlugInfo"
  lazy val sqsSlugDeadLetterQueue: URL = url"$sqsQueueUrlPrefix/$sqsQueueSlugInfo-deadletter"

  private lazy val sqsQueueDeploymentInfo =
    configuration.get[String]("artefact.receiver.aws.sqs.queue-deployment")

  lazy val sqsDeploymentQueue          : URL = url"$sqsQueueUrlPrefix/$sqsQueueDeploymentInfo"
  lazy val sqsDeploymentDeadLetterQueue: URL = url"$sqsQueueUrlPrefix/$sqsQueueDeploymentInfo-deadletter"

  lazy val sqsDeadLetterQueues: Set[URL] =
    Set(
      sqsSlugDeadLetterQueue,
      sqsDeploymentDeadLetterQueue
    )

  lazy val isEnabled             : Boolean = configuration.get[Boolean]("artefact.receiver.enabled")
}
