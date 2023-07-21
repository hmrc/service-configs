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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{ServiceName, ServiceRelationship}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceRelationshipRepository @Inject()(
  override val mongoComponent: MongoComponent,
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
) with Logging
  with Transactions
{
  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

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
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        _ <- collection.insertMany(session, srs).toFuture()
      } yield ()
    }
}
