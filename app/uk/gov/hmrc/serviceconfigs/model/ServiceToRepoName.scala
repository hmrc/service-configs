/*
 * Copyright 2024 HM Revenue & Customs
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

import play.api.libs.functional.syntax.{toFunctionalBuilderOps, unlift}
import play.api.libs.json.{Format, __}

case class ServiceToRepoName(
  serviceName : ServiceName,
  artefactName: ArtefactName,
  repoName    : RepoName
)

object ServiceToRepoName {
  val mongoFormat: Format[ServiceToRepoName] =
    ( (__ \ "serviceName" ).format[ServiceName](ServiceName.format)
    ~ (__ \ "artefactName").format[ArtefactName](ArtefactName.format)
    ~ (__ \ "repoName"    ).format[RepoName](RepoName.format)
    ) (ServiceToRepoName.apply, pt => Tuple.fromProductTyped(pt))
}
