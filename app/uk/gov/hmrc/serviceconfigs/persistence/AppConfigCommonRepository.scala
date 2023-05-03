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

import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
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
) extends PlayMongoRepository[(String, String, String)](
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

  def put(fileName: String, commitId: String, content: String): Future[Unit] =
    // TODO how to clean up unreferenced versions?
    // TODO we only really need to replace if commitId is the same (e.g. "HEAD")
    collection.replaceOne(
      and(
        equal("fileName", fileName),
        equal("commitId", commitId)
      ),
      (fileName, commitId, content),
      ReplaceOptions().upsert(true)
    )
      .toFuture()
      .map(_ => ())

  def putAllHEAD(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("commiId", "HEAD")).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (a, b) => (a, "HEAD", b) }).toFuture()
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
      .map(_.map(_._2))
}

object AppConfigCommonRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val mongoFormats: Format[(String, String, String)] =
     ( (__ \ "fileName").format[String]
     ~ (__ \ "commitId").format[String]
     ~ (__ \ "content" ).format[String]
     ).tupled
}
