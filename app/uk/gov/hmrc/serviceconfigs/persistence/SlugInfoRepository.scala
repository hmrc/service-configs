/*
 * Copyright 2021 HM Revenue & Customs
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

import com.mongodb.BasicDBObject
import javax.inject.{Inject, Singleton}
import org.mongodb.scala.model.Filters.{equal, and}
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Indexes}
import play.api.Logging
import org.mongodb.scala.model.Updates.set
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.{MongoSlugInfoFormats, SlugInfo, SlugInfoFlag, Version}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoRepository @Inject()(
  mongoComponent: MongoComponent
)( implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "slugConfigurations",
  domainFormat   = MongoSlugInfoFormats.siFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("uri"), IndexOptions().unique(true).name("slugInfoUniqueIdx")),
                     IndexModel(Indexes.hashed("name"), IndexOptions().background(true).name("slugInfoIdx")),
                     IndexModel(Indexes.hashed("latest"), IndexOptions().background(true).name("slugInfoLatestIdx"))
                   )
) with Logging {

  def add(slugInfo: SlugInfo): Future[Unit] =
    collection
      .findOneAndReplace(
         filter      = equal("uri", slugInfo.uri),
         replacement = slugInfo,
         options     = FindOneAndReplaceOptions().upsert(true)
       )
      .toFutureOption()
      .map(_ => ())

  def clearAll(): Future[Boolean] =
    collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())

  def getSlugInfo(
    name: String,
    flag: SlugInfoFlag = SlugInfoFlag.Latest
  ): Future[Option[SlugInfo]] =
    collection
      .find(and(equal("name", name), equal(flag.asString, true)))
      .first()
      .toFutureOption()

  def getSlugInfos(name: String, optVersion: Option[Version]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => collection.find(equal("name", name)).toFuture()
      case Some(version) => collection.find(
                                and(
                                  equal("name", name)
                                , equal("version", version.original)
                                )
                              ).toFuture()
    }

  def getUniqueSlugNames: Future[Seq[String]] =
    collection.distinct[String]("name")
      .toFuture

  def clearFlag(flag: SlugInfoFlag, name: String): Future[Unit] =
   for {
    _ <- Future.successful(logger.debug(s"clear ${flag.asString} flag on $name"))
    _ <- collection
           .updateMany(
               filter = equal("name", name)
             , update = set(flag.asString, false)
             )
           .toFuture
   } yield ()

  def setFlag(flag: SlugInfoFlag, name: String, version: Version): Future[Unit] =
    for {
      _ <- clearFlag(flag, name)
      _ =  logger.debug(s"mark slug $name $version with ${flag.asString} flag")
      _ <- collection
             .updateOne(
                 filter = and( equal("name", name)
                             , equal("version", version.original)
                             )
               , update = set(flag.asString, true))
             .toFuture
    } yield ()
}
