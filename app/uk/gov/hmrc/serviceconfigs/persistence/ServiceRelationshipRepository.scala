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

package uk.gov.hmrc.serviceconfigs.persistence

import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes, ReplaceOneModel, ReplaceOptions, DeleteOneModel}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{ServiceName, ServiceRelationship}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceRelationshipRepository @Inject()(
  mongoComponent: MongoComponent,
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[ServiceRelationship](
  mongoComponent = mongoComponent,
  collectionName = "serviceRelationships",
  domainFormat   = ServiceRelationship.serviceRelationshipFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("source"), IndexOptions().name("srSourceIdx")),
                     IndexModel(Indexes.ascending("target"), IndexOptions().name("srTargetIdx"))
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
) with Logging {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def getInboundServices(serviceName: ServiceName): Future[Seq[ServiceName]] =
    collection
      .find(Filters.equal("target", serviceName))
      .toFuture()
      .map(_.map(_.source))

  def getOutboundServices(serviceName: ServiceName): Future[Seq[ServiceName]] =
    collection
      .find(Filters.equal("source", serviceName))
      .toFuture()
      .map(_.map(_.target))

  def putAll(srs: Seq[ServiceRelationship]): Future[Unit] =
    for {
      old         <- collection.find().toFuture()
      bulkUpdates =  //upsert any that were not present already
                     srs
                       .filterNot(old.contains)
                       .map(entry =>
                         ReplaceOneModel(
                           Filters.and(
                             Filters.equal("source", entry.source),
                             Filters.equal("target", entry.target)
                           ),
                           entry,
                           ReplaceOptions().upsert(true)
                         )
                       ) ++
                     // delete any that are not longer present
                       old.filterNot(oldC =>
                         srs.exists(newC =>
                           newC.source == oldC.source &&
                           newC.target == oldC.target
                         )
                       ).map(entry =>
                         DeleteOneModel(
                           Filters.and(
                             Filters.equal("source", entry.source),
                             Filters.equal("target", entry.target)
                           )
                         )
                       )
       _          <- if (bulkUpdates.isEmpty) Future.unit
                     else collection.bulkWrite(bulkUpdates).toFuture().map(_=> ())
    } yield ()
}
