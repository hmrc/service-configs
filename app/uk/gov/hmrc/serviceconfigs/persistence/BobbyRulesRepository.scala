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
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

// TODO introduce BobbyRule model
@Singleton
class BobbyRulesRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[String](
  mongoComponent = mongoComponent,
  collectionName = "bobbyRules",
  domainFormat   = BobbyRulesRepository.format,
  indexes        = Seq.empty
) {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def putAll(config: String): Future[Unit] =
    collection
      .replaceOne(
        filter      = BsonDocument(),
        replacement = config,
        options     = new ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def findAll(): Future[String] =
    collection
      .find()
      .head()
}

object BobbyRulesRepository {
  import play.api.libs.json.{Format, __}

  val format: Format[String] =
    Format.at(__ \ "bobbyRules")
}
