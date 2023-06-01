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

import org.mongodb.scala.ClientSession
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Indexes}
import play.api.Logging
import org.mongodb.scala.model.Updates._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{MongoSlugInfoFormats, SlugInfo, SlugInfoFlag, ServiceName, Version}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugInfoRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = SlugInfoRepository.collectionName,
  domainFormat   = MongoSlugInfoFormats.slugInfoFormat,
  indexes        = SlugInfoRepository.indexes,
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
) with Logging
  with Transactions {

  // we delete explicitly when we get a delete notification
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def add(slugInfo: SlugInfo): Future[Unit] =
    collection
      .findOneAndReplace(
         filter      = equal("uri", slugInfo.uri),
         replacement = slugInfo,
         options     = FindOneAndReplaceOptions().upsert(true)
       )
      .toFutureOption()
      .map(_ => ())

  def delete(name: ServiceName, version: Version): Future[Unit] =
    collection
      .deleteOne(
          and(
            equal("name"   , name),
            equal("version", version.toString)
          )
        )
      .toFuture()
      .map(_ => ())

  def getSlugInfo(
    name: ServiceName,
    flag: SlugInfoFlag = SlugInfoFlag.Latest
  ): Future[Option[SlugInfo]] =
    collection
      .find(
        and(
          equal("name"       , name),
          equal(flag.asString, true)
        )
      )
      .first()
      .toFutureOption()

  def getSlugInfos(name: ServiceName, optVersion: Option[Version]): Future[Seq[SlugInfo]] =
    optVersion match {
      case None          => collection.find(equal("name", name)).toFuture()
      case Some(version) => collection.find(
                                and(
                                  equal("name"   , name.asString)
                                , equal("version", version.original)
                                )
                              ).toFuture()
    }

  def getAllLatestSlugInfos(): Future[Seq[SlugInfo]] =
    collection.find(equal("latest", value = true)).toFuture()

  def getUniqueSlugNames(): Future[Seq[ServiceName]] =
    collection.distinct[String]("name")
      .toFuture()
      .map(_.map(ServiceName.apply))

  def clearFlag(flag: SlugInfoFlag, name: ServiceName): Future[Unit] =
    withSessionAndTransaction { session =>
      clearFlag(flag, name, session)
    }

  private def clearFlag(flag: SlugInfoFlag, name: ServiceName, session: ClientSession): Future[Unit] =
   for {
    _ <- Future.successful(logger.debug(s"clear ${flag.asString} flag on ${name.asString}"))
    _ <- collection
           .updateMany(
               clientSession = session
             , filter        = and( equal("name", name)
                                  , equal(flag.asString, true)
                                  )
             , update        = set(flag.asString, false)
             )
           .toFuture()
   } yield ()

  def clearFlags(flags: List[SlugInfoFlag], names: List[ServiceName]): Future[Unit] = {
    logger.debug(s"Clearing ${flags.size} flags on ${names.size} services")
    collection
      .updateMany(
        filter = in("name", names: _ *),
        update = combine(flags.map(flag => set(flag.asString, false)): _ *)
      )
      .toFuture()
      .map(_ => ())
  }

  def setFlag(flag: SlugInfoFlag, name: ServiceName, version: Version): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- clearFlag(flag, name, session)
        _ =  logger.debug(s"mark slug ${name.asString} $version with ${flag.asString} flag")
        _ <- collection
               .updateOne(
                   clientSession = session
                 , filter        = and( equal("name", name)
                                      , equal("version", version.original)
                                      )
                 , update        = set(flag.asString, true))
               .toFuture()
      } yield ()
    }
}


object SlugInfoRepository {
  val collectionName: String =
    "slugConfigurations"

  val indexes: Seq[IndexModel] =
    Seq(
      IndexModel(Indexes.ascending("uri"), IndexOptions().unique(true).name("slugInfoUniqueIdx")),
      IndexModel(Indexes.hashed("name"), IndexOptions().background(true).name("slugInfoIdx")),
      IndexModel(Indexes.hashed("latest"), IndexOptions().background(true).name("slugInfoLatestIdx")),
      IndexModel(
        Indexes.compoundIndex(Indexes.ascending("name"), Indexes.descending("version")),
        IndexOptions().name("slugInfoNameVersionIdx").background(true)
      )
    )
}
