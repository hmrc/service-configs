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

package uk.gov.hmrc.serviceconfigs.model

import play.api.libs.json.Format
import play.api.libs.functional.syntax._

case class CommitId(asString: String) extends AnyVal

case class RepoName(asString: String) extends AnyVal

case class FileName(asString: String) extends AnyVal

case class ServiceName(asString: String) extends AnyVal {
  def trimmed: ServiceName = ServiceName(asString.trim)
}

case class Tag(asString: String) extends AnyVal

case class TeamName(asString: String) extends AnyVal {
  def trimmed: TeamName = TeamName(asString.trim)
}

object ServiceName {
  val format =
    implicitly[Format[String]].inmap(ServiceName.apply, unlift(ServiceName.unapply))
}
