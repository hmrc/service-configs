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

package uk.gov.hmrc.serviceconfigs.persistence

import org.mongodb.scala.model.Filters.{and, empty, equal}
import org.mongodb.scala.model.{IndexModel, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{ArtefactName, RepoName, ServiceName, ServiceToRepoName}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceToRepoNameRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[ServiceToRepoName](
  mongoComponent = mongoComponent,
  collectionName = "serviceToRepoNames",
  domainFormat   = ServiceToRepoName.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("serviceName")),
                     IndexModel(Indexes.ascending("artefactName"))
                   ),
  extraCodecs    = Seq(
                     Codecs.playFormatCodec(ArtefactName.format),
                     Codecs.playFormatCodec(ServiceName.format),
                     Codecs.playFormatCodec(RepoName.format)
                   )
):
  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def findRepoName(serviceName: Option[ServiceName] = None, artefactName: Option[ArtefactName] = None): Future[Option[RepoName]] =
    val filters = Seq(
      serviceName.map(sn => equal("serviceName", sn)),
      artefactName.map(an => equal("artefactName", an))
    ).flatten

    collection
      .find(if (filters.nonEmpty) and(filters: _*) else empty())
      .map(_.repoName)
      .headOption()

  def putAll(serviceToRepoNames: Seq[ServiceToRepoName]): Future[Unit] =
    MongoUtils.replace[ServiceToRepoName](
      collection    = collection,
      newVals       = serviceToRepoNames,
      compareById   = (a, b) =>
                        a.serviceName  == b.serviceName &&
                        a.artefactName == b.artefactName &&
                        a.repoName     == b.repoName,
      filterById    = entry =>
                        and(
                          equal("serviceName" , entry.serviceName),
                          equal("artefactName", entry.artefactName),
                          equal("repoName"    , entry.repoName)
                        )
    )
