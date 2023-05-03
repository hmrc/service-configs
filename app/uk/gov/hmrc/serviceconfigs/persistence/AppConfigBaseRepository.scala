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

import org.mongodb.scala.model.Filters.{and, equal, notEqual}
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppConfigBaseRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[(String, Option[Environment], String, String)](
  mongoComponent = mongoComponent,
  collectionName = "appConfigBase",
  domainFormat   = AppConfigBaseRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(hashed("fileName"), IndexOptions().name("fileNameIdx"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format)
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  // @param environment helps us ensure we only keep one commitId per environment
  // Note, we don't need environment when HEAD (TODO model this better)
  def put(fileName: String, environment: Environment, commitId: String, content: String): Future[Unit] =
    collection
      .replaceOne(
        and(
          equal("environment", environment),
          notEqual("commitId", "HEAD"),
          equal("fileName", fileName)
        ),
        (fileName, Some(environment), commitId, content),
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def putAllHEAD(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("commitId", "HEAD")).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) => (fileName, None, "HEAD", content) }).toFuture()
      } yield ()
    }

  def findByFileName(fileName: String, commitId: String): Future[Option[String]] =
    collection
      .find(
        and(
          equal("fileName", fileName),
          equal("commitId", commitId)
        )
      )
      .headOption()
      .map(_.map(_._4))
}

object AppConfigBaseRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val mongoFormats: Format[(String, Option[Environment], String, String)] = {
    implicit val ef = Environment.format
     ( (__ \ "fileName"   ).format[String]
     ~ (__ \ "environment").formatNullable[Environment]
     ~ (__ \ "commitId"   ).format[String]
     ~ (__ \ "content"    ).format[String]
     ).tupled
  }
}
