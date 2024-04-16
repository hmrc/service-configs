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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{Environment, OutagePage, ServiceName}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OutagePageRepository @Inject() (
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[OutagePage](
  mongoComponent = mongoComponent
, collectionName = "outagePages"
, domainFormat   = OutagePage.outagePageFormat
, indexes        = Seq(IndexModel(ascending("serviceName"), IndexOptions().unique(true)))
, extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
){

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def findByServiceName(serviceName: ServiceName): Future[Option[Seq[Environment]]] =
    collection.find(equal("serviceName", serviceName))
      .headOption()
      .map(_.map(_.environments))

  def putAll(outagePages: Seq[OutagePage]): Future[Unit] =
    MongoUtils.replace[OutagePage](
      collection    = collection,
      newVals       = outagePages,
      compareById   = (a, b) => a.serviceName == b.serviceName,
      filterById    = entry =>
                        equal("serviceName", entry.serviceName)
    )
}
