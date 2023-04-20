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
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.LastHash

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}


// TODO drop collection before deploying
@Singleton
class LastHashRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "lastHashString",
  domainFormat   = LastHash.formats,
  indexes        = Seq(
                     IndexModel(ascending("key"), IndexOptions().unique(true))
                   )
) {

  def update(key: String, hash: String): Future[Unit] =
    collection
      .replaceOne(
        filter      = equal("key", key),
        replacement = LastHash(key, hash),
        options     = new ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

    def getHash(key: String): Future[Option[String]] =
      collection
        .find(equal("key", key))
        .toFuture()
        .map(_.headOption)
        .map(_.map(_.hash))
  }
