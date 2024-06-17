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

import uk.gov.hmrc.serviceconfigs.model.Version

sealed trait MessagePayload

object MessagePayload:
  import play.api.libs.json.{Reads, __}
  import play.api.libs.functional.syntax._

  case class JobAvailable(
    jobType: String,
    name   : String,
    version: Version,
    url    : String
  ) extends MessagePayload

  case class JobDeleted(
    jobType: String,
    name   : String,
    version: Version,
    url    : String
  ) extends MessagePayload

  private val jobAvailableReads: Reads[JobAvailable] =
    ( (__ \ "jobType").read[String]
    ~ (__ \ "name"   ).read[String]
    ~ (__ \ "version").read[Version](Version.format)
    ~ (__ \ "url"    ).read[String]
    )(JobAvailable.apply _)

  private val jobDeletedReads: Reads[JobDeleted] =
    ( (__ \ "jobType").read[String]
    ~ (__ \ "name"   ).read[String]
    ~ (__ \ "version").read[Version](Version.format)
    ~ (__ \ "url"    ).read[String]
    )(JobDeleted.apply _)

  val reads: Reads[MessagePayload] =
    (__ \ "type").read[String].flatMap:
      case "creation" => jobAvailableReads.map[MessagePayload](identity)
      case "deletion" => jobDeletedReads.map[MessagePayload](identity)
