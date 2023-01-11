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

import com.mongodb.client.model.Indexes

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.AdminFrontendRoute

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AdminFrontendRouteRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "adminFrontendRoutes",
  domainFormat   = AdminFrontendRoute.format,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("route"),   IndexOptions().background(true).name("routeIdx")),
                     IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx"))
                   ),
) with Transactions {

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def findByService(service: String): Future[Seq[AdminFrontendRoute]] =
    collection
      .find(equal("service", service))
      .toFuture()

  def replaceAll(routes: Seq[AdminFrontendRoute]): Future[Int] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        r <- collection.insertMany(session, routes).toFuture()
      } yield r.getInsertedIds.size
    }

  def findAllAdminFrontendServices(): Future[Seq[String]] =
    collection
      .distinct[String]("service")
      .toFuture()
}
