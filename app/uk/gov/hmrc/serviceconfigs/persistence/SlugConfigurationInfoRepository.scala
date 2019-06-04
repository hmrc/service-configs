/*
 * Copyright 2019 HM Revenue & Customs
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
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.serviceconfigs.model.{MongoSlugInfoFormats, SlugInfo, SlugInfoFlag}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugConfigurationInfoRepository @Inject()
                                             (mongo: ReactiveMongoComponent)
                                             (implicit executionContext: ExecutionContext)
  extends ReactiveRepository[SlugInfo, BSONObjectID] (
    collectionName = "slugConfigurations",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = MongoSlugInfoFormats.siFormat) {

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("uri" -> IndexType.Ascending),
        name   = Some("slugInfoUniqueIdx"),
        unique = true),
      Index(
        Seq("name" -> IndexType.Hashed),
        name       = Some("slugInfoIdx"),
        background = true),
      Index(
        Seq("latest" -> IndexType.Hashed),
        name       = Some("slugInfoLatestIdx"),
        background = true))

  import MongoSlugInfoFormats.siFormat

  def add(slugInfo: SlugInfo): Future[Boolean] =
    collection
      .update(
        selector = Json.obj("uri" -> Json.toJson(slugInfo.uri)),
        update   = slugInfo,
        upsert   = true)
      .map(_.ok)

  def clearAll(): Future[Boolean] =
    super.removeAll().map(_.ok)

  def getSlugInfo(name: String, flag: SlugInfoFlag = SlugInfoFlag.Latest): Future[Option[SlugInfo]] =
    find(
      "name" -> name,
      flag.s -> true)
      .map(_.headOption)

  def getSlugInfos(name: String, optVersion: Option[String]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => find("name" -> name)
      case Some(version) => find("name" -> name, "version" -> version)
    }
}