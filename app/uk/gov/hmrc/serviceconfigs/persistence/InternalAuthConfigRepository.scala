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
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import play.api.Logging
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{GrantType, InternalAuthConfig, InternalAuthEnvironment, ServiceName}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


@Singleton
class InternalAuthConfigRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "internalAuthConfig",
  domainFormat   = InternalAuthConfig.format,
  indexes        = Seq(
    IndexModel(Indexes.hashed("serviceName"), IndexOptions().background(true).name("intAuthServiceNameIdx"))
  ),
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
) with Transactions with Logging {

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict


  def findByService(serviceName: ServiceName): Future[Seq[InternalAuthConfig]] =
    collection
      .find(equal("serviceName", serviceName))
      .toFuture()

  def putAll(internalAuthConfigs: Set[InternalAuthConfig]) : Future[Int] = {
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        r <- collection.insertMany(session, internalAuthConfigs.toSeq).toFuture()
      } yield r.getInsertedIds.size()
    }
  }

}
