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
import org.mongodb.scala.MongoCollection

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}

import scala.concurrent.{ExecutionContext, Future}

object AdminFrontendRouteRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  case class AdminFrontendRoute(
    service : String
  , route   : String
  , allow   : Map[String, List[String]]
  , location: String
  )

  val format: Format[AdminFrontendRoute] =
    ( (__ \ "service" ).format[String]
    ~ (__ \ "route"   ).format[String]
    ~ (__ \ "allow"   ).format[Map[String, List[String]]]
    ~ (__ \ "location").format[String]
    )(AdminFrontendRoute.apply, unlift(AdminFrontendRoute.unapply))
}

@Singleton
class AdminFrontendRouteRepository @Inject()(
  final val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "adminFrontendRoutes",
  domainFormat   = AdminFrontendRouteRepository.format,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("route"),   IndexOptions().background(true).name("routeIdx")),
                     IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx"))
                   ),
) with Transactions {
  import AdminFrontendRouteRepository._

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def findByService(service: String): Future[Seq[AdminFrontendRoute]] =
    collection
      .find(equal("service", service))
      .toFuture()

  def replaceAll(routes: Seq[AdminFrontendRoute]): Future[Int] = {
    withSessionAndTransaction { session =>
      for {
        _  <- collection.deleteMany(session, BsonDocument()).toFuture()
        xs <- collection.insertMany(session, routes).toFuture().map(_.getInsertedIds)
      } yield xs.size
    }
  }
}
