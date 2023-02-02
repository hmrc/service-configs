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

import com.mongodb.BasicDBObject
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions, Indexes}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.ServiceRelationship

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceRelationshipRepository @Inject()(
  override val mongoComponent: MongoComponent,
)(implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = ServiceRelationshipRepository.collectionName,
  domainFormat   = ServiceRelationship.serviceRelationshipFormat,
  indexes        = ServiceRelationshipRepository.indexes
) with Logging
  with Transactions
{

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def getInboundServices(service: String): Future[Seq[String]] =
    collection
      .find(Filters.equal("target", service))
      .toFuture()
      .map(srs => srs.map(_.source))

  def getOutboundServices(service: String): Future[Seq[String]] =
    collection
      .find(Filters.equal("source", service))
      .toFuture()
      .map(srs => srs.map(_.target))

  def putAll(srs: Seq[ServiceRelationship]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, new BasicDBObject()).toFuture()
        _ <- collection.insertMany(session, srs).toFuture()
      } yield ()
    }

}

object ServiceRelationshipRepository {
  val collectionName: String = "serviceRelationships"

  val indexes: Seq[IndexModel] =
    Seq(
      IndexModel(Indexes.ascending("source"), IndexOptions().name("srSourceIdx")),
      IndexModel(Indexes.ascending("target"), IndexOptions().name("srTargetIdx"))
    )
}