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

import com.mongodb.client.model.ReplaceOptions
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{equal, exists}
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, LastHash}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AlertEnvironmentHandlerRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "alertEnvironmentHandlers",
  domainFormat   = AlertEnvironmentHandler.mongoFormats,
  indexes        = Seq(
                     IndexModel(hashed("serviceName"), IndexOptions().background(true).name("serviceNameIdx"))
                   )
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def putAll(alertEnvironmentHandlers: Seq[AlertEnvironmentHandler]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        _ <- collection.insertMany(session, alertEnvironmentHandlers).toFuture()
      } yield ()
    }

  def findByServiceName(serviceName: String): Future[Option[AlertEnvironmentHandler]] =
    collection
      .find(equal("serviceName", serviceName))
      .headOption()


  def findAll(): Future[Seq[AlertEnvironmentHandler]] =
    collection
      .find()
      .toFuture()
      .map(_.toList)
}

@Singleton
class AlertHashStringRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "lastHashString",
  domainFormat   = LastHash.formats,
  indexes        = Seq(
                     IndexModel(ascending("hash"), IndexOptions().unique(true).background(true).name("hashUniqIdx"))
                   )
) {

  def update(hash: String): Future[Unit] =
    collection
      .replaceOne(
        filter      = exists("hash"),
        replacement = LastHash(hash),
        options     = new ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

    def findOne(): Future[Option[LastHash]] =
      collection
        .find()
        .toFuture()
        .map(_.headOption)
  }
