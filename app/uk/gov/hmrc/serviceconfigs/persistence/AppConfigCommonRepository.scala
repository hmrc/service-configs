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
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppConfigCommonRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[AppConfigCommonRepository.AppConfigCommon](
  mongoComponent = mongoComponent,
  collectionName = "appConfigCommon",
  domainFormat   = AppConfigCommonRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(hashed("fileName"), IndexOptions().name("fileNameIdx"))
                   )
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  private val logger = Logger(getClass)

  def put(appConfig: AppConfigCommonRepository.AppConfigCommon): Future[Unit] =
    appConfig.serviceName match {
      case Some(serviceName) =>
        collection
          .replaceOne(
            and(
              equal("serviceName", serviceName),
              equal("fileName"   , appConfig.fileName)
            ),
            appConfig,
            ReplaceOptions().upsert(true)
          )
          .toFuture()
          .map(_ => ())
      case None =>
        logger.warn(s"Skipping store of appConfig since no environment specified - should be stored with putAllHEAD")
        // or we just support storing with HEAD instead?
        Future.unit
    }

  def putAllHEAD(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) =>
               AppConfigCommonRepository.AppConfigCommon(
                 serviceName = None,
                 commitId    = "HEAD",
                 fileName    = fileName,
                 content     = content
               )
             }).toFuture()
      } yield ()
    }

  def find(serviceName: String, fileName: String): Future[Option[String]] =
    collection
      .find(
        and(
          equal("serviceName", serviceName),
          equal("fileName"   , fileName)
        )
      )
      .headOption()
      .map(_.map(_.content))

  def findHEAD(fileName: String): Future[Option[String]] =
    collection
      .find(
        and(
          equal("fileName", fileName),
          equal("commitId", "HEAD")
        )
      )
      .headOption()
      .map(_.map(_.content))
}

object AppConfigCommonRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  case class AppConfigCommon(
    serviceName: Option[String], // only applies to non-HEAD
    commitId   : String,
    fileName   : String,
    content    : String
  )

  val mongoFormats: Format[AppConfigCommon] =
     ( (__ \ "serviceName").formatNullable[String]
     ~ (__ \ "commitId"   ).format[String]
     ~ (__ \ "fileName"   ).format[String]
     ~ (__ \ "content"    ).format[String]
     )(AppConfigCommon.apply, unlift(AppConfigCommon.unapply))
}
