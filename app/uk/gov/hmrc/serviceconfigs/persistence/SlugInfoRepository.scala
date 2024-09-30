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

import play.api.Logging
import org.mongodb.scala.{ClientSession, ClientSessionOptions, ObservableFuture, ReadConcern, ReadPreference, SingleObservableFuture, TransactionOptions, WriteConcern}
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Indexes, Projections}
import org.mongodb.scala.model.Filters._
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
)(using ec: ExecutionContext
) extends PlayMongoRepository[SlugInfo](
  mongoComponent = mongoComponent,
  collectionName = SlugInfoRepository.collectionName,
  domainFormat   = MongoSlugInfoFormats.slugInfoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("uri"), IndexOptions().unique(true)),
                     IndexModel(Indexes.hashed("name"), IndexOptions().background(true)),
                     IndexModel(Indexes.hashed("latest"), IndexOptions().background(true)),
                     IndexModel(Indexes.compoundIndex(Indexes.ascending("name"), Indexes.descending("version")), IndexOptions().background(true))
                   ) ++
                     SlugInfoFlag.values.map { flag =>
                       IndexModel(Indexes.compoundIndex(Indexes.hashed("name"), Indexes.ascending(flag.asString)), IndexOptions().background(true).sparse(true))
                     },
  extraCodecs    = Seq(
                     Codecs.playFormatCodec(ServiceName.format),
                     Codecs.playFormatCodec(Version.mongoVersionRepositoryFormat)
                   ),
  replaceIndexes = true
) with Logging
  with Transactions:

  // we delete explicitly when we get a delete notification
  override lazy val requiresTtlIndex = false

  private given TransactionConfiguration =
    TransactionConfiguration(
      clientSessionOptions = Some(
                               ClientSessionOptions.builder()
                                 .causallyConsistent(true)
                                 .build()
                             ),
      transactionOptions   = Some(
                               TransactionOptions.builder()
                                 .readConcern(ReadConcern.MAJORITY)
                                 .writeConcern(WriteConcern.MAJORITY)
                                 .readPreference(ReadPreference.primary())
                                 .build()
                             )
    )

  def add(slugInfo: SlugInfo): Future[Unit] =
    withSessionAndTransaction: session =>
      for
        oMaxVersion <- getMaxVersion(slugInfo.name, session)
        isLatest    =  oMaxVersion.fold(true)(_ <= slugInfo.version)
        _           <- collection
                         .findOneAndReplace(
                           clientSession = session
                         , filter        = equal("uri", slugInfo.uri)
                         , replacement   = slugInfo
                         , options       = FindOneAndReplaceOptions().upsert(true)
                         )
                         .toFutureOption()
        _           =  logger.info(s"Slug ${slugInfo.name.asString} ${slugInfo.version.original} isLatest=$isLatest (max is: $oMaxVersion)")
        _           <- if   isLatest
                       then setFlag(SlugInfoFlag.Latest, slugInfo.name, slugInfo.version, session)
                       else Future.unit
      yield ()

  def delete(name: ServiceName, version: Version): Future[Unit] =
    collection
      .deleteOne(
          and(
            equal("name"   , name),
            equal("version", version.original)
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

  def getSlugInfos(name: ServiceName, version: Option[Version]): Future[Seq[SlugInfo]] =
    collection
      .find(
        and(
          equal("name", name.asString)
        , version.fold(empty())(v => equal("version", v.original))
        )
      )
      .toFuture()

  def getAllLatestSlugInfos(): Future[Seq[SlugInfo]] =
    collection.find(equal("latest", value = true)).toFuture()

  def getUniqueSlugNames(): Future[Seq[ServiceName]] =
    collection.distinct[String]("name")
      .toFuture()
      .map(_.map(ServiceName.apply))

  def clearFlag(flag: SlugInfoFlag, name: ServiceName): Future[Unit] =
    withSessionAndTransaction: session =>
      clearFlag(flag, name, session)

  private def clearFlag(flag: SlugInfoFlag, name: ServiceName, session: ClientSession): Future[Unit] =
    for
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
    yield ()

  def clearFlags(flag: SlugInfoFlag, names: Seq[ServiceName]): Future[Unit] =
    logger.info(s"Clearing ${flag.asString} flag on ${names.size} services")
    collection
      .updateMany(
        filter = and( in("name", names: _ *)
                    , equal(flag.asString, true)
                    )
      , update = set(flag.asString, false)
      )
      .toFuture()
      .map(_ => ())

  def setFlag(flag: SlugInfoFlag, name: ServiceName, version: Version): Future[Unit] =
    withSessionAndTransaction: session =>
      setFlag(flag, name, version, session)

  def setFlag(flag: SlugInfoFlag, name: ServiceName, version: Version, session: ClientSession): Future[Unit] =
    for
      _ <- clearFlag(flag, name, session)
      _ =  logger.info(s"mark slug ${name.asString} $version with ${flag.asString} flag")
      _ <- collection
             .updateOne(
                 clientSession = session
               , filter        = and( equal("name", name)
                                    , equal("version", version.original)
                                    )
               , update        = set(flag.asString, true))
             .toFuture()
    yield ()

  def getMaxVersion(name: ServiceName): Future[Option[Version]] =
    withSessionAndTransaction: session =>
      getMaxVersion(name, session)

  def getMaxVersion(name: ServiceName, session: ClientSession): Future[Option[Version]] =
    collection
      .find[Version](session, equal("name", name))
      .projection(Projections.include("version"))
      .foldLeft(Option.empty[Version]):
        case (optMax, version) if optMax.exists(_ > version) => optMax
        case (_     , version)                               => Some(version)
      .toFuture()

object SlugInfoRepository:
  val collectionName = "slugConfigurations"
