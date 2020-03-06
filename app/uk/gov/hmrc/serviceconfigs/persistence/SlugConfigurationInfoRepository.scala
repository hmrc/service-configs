/*
 * Copyright 2020 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import com.mongodb.BasicDBObject
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.{MongoSlugInfoFormats, SlugInfo, SlugInfoFlag}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugConfigurationInfoRepository @Inject()(mongoComponent: MongoComponent)(
  implicit executionContext: ExecutionContext
) extends PlayMongoRepository(
      mongoComponent = mongoComponent,
      collectionName = "slugConfigurations",
      domainFormat   = MongoSlugInfoFormats.siFormat,
      indexes = Seq(
        IndexModel(Indexes.ascending("uri"), IndexOptions().unique(true).name("slugInfoUniqueIdx")),
        IndexModel(Indexes.hashed("name"), IndexOptions().background(true).name("slugInfoIdx")),
        IndexModel(Indexes.hashed("latest"), IndexOptions().background(true).name("slugInfoLatestIdx"))
      )
    ) {

  def add(slugInfo: SlugInfo): Future[Unit] = {
    val filter = equal("uri", slugInfo.uri)

    val options = FindOneAndReplaceOptions().upsert(true)
    collection
      .findOneAndReplace(filter = filter, replacement = slugInfo, options = options)
      .toFutureOption()
      .map(_ => ())
  }

  def clearAll(): Future[Boolean] = collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())

  def getSlugInfo(
    name: String,
    flag: SlugInfoFlag = SlugInfoFlag.Latest
  ): Future[Option[SlugInfo]] =
    collection
      .find(and(equal("name", name), equal(flag.s, true)))
      .first()
      .toFutureOption()

  def getSlugInfos(name: String, optVersion: Option[String]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => collection.find(equal("name", name)).toFuture()
      case Some(version) => collection.find(and(equal("name", name), equal("version", version))).toFuture()
    }
}
