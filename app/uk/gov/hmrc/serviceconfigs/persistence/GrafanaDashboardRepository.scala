/*
 * Copyright 2022 HM Revenue & Customs
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

import com.mongodb.client.model.Indexes

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.Dashboard

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GrafanaDashboardRepository @Inject()(
  final val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "grafanaDashboards",
  domainFormat   = Dashboard.format,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx"))
                   ),
) with Transactions {

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def findByService(service: String): Future[Option[Dashboard]] =
    collection
      .find(equal("service", service))
      .headOption()

  def replaceAll(rows: Seq[Dashboard]): Future[Int] = {
    withSessionAndTransaction { session =>
      for {
        _  <- collection.deleteMany(session, BsonDocument()).toFuture()
        xs <- collection.insertMany(session, rows).toFuture().map(_.getInsertedIds)
      } yield xs.size
    }
  }
}
