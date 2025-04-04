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

import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{Reads, Writes, __}

case class ServiceToRepoName(
  serviceName : ServiceName,
  artefactName: ArtefactName,
  repoName    : RepoName,
  disabled    : Boolean
)

object ServiceToRepoName:
  val reads: Reads[ServiceToRepoName] =
    ( (__ \ "serviceName" ).read[ServiceName](ServiceName.format)
    ~ (__ \ "artefactName").read[ArtefactName](ArtefactName.format)
    ~ (__ \ "repoName"    ).read[RepoName](RepoName.format)
    ~ (__ \ "disabled"    ).read[Boolean]
    )(ServiceToRepoName.apply _)

  val apiWrites: Writes[ServiceToRepoName] =
    ( (__ \ "serviceName" ).write[ServiceName](ServiceName.format)
    ~ (__ \ "artefactName").write[ArtefactName](ArtefactName.format)
    ~ (__ \ "repoName"    ).write[RepoName](RepoName.format)
    ~ (__ \ "disabled"    ).write[Boolean]
    )(o => Tuple.fromProductTyped(o))
