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
import org.mongodb.scala.model.{Indexes, IndexModel, ReplaceOptions}
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
                     IndexModel(Indexes.ascending("serviceName", "fileName", "commitId")),
                     IndexModel(Indexes.ascending("commitId"))
                   )
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def put(serviceName: String, fileName: String, commitId: String, content: String): Future[Unit] =
    collection
      .replaceOne(
        filter      = and(
                        equal("serviceName", serviceName),
                        equal("fileName"   , fileName)
                      ),
        replacement = AppConfigCommonRepository.AppConfigCommon(
                        serviceName = Some(serviceName),
                        fileName    = fileName,
                        commitId    = commitId,
                        content     = content
                      ),
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def putAllHEAD(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("commitId", "HEAD")).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) =>
               AppConfigCommonRepository.AppConfigCommon(
                 serviceName = None,
                 fileName    = fileName,
                 commitId    = "HEAD",
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

  def deleteNonHEAD(serviceName: String, fileName: String): Future[Unit] =
    collection.deleteOne(
      and(
        equal("serviceName", serviceName),
        equal("fileName", fileName),
        notEqual("comitId", "HEAD")
      )
    ).toFuture()
     .map(_ => ())
}

object AppConfigCommonRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  case class AppConfigCommon(
    serviceName: Option[String], // only applies to non-HEAD
    fileName   : String,
    commitId   : String,
    content    : String
  )

  val mongoFormats: Format[AppConfigCommon] =
     ( (__ \ "serviceName").formatNullable[String]
     ~ (__ \ "fileName"   ).format[String]
     ~ (__ \ "commitId"   ).format[String]
     ~ (__ \ "content"    ).format[String]
     )(AppConfigCommon.apply, unlift(AppConfigCommon.unapply))
}
