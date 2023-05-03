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
) extends PlayMongoRepository[AppConfigBaseRepository.AppConfigBase](
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
  // @param serviceName for consistency (not strictly necessary here since we only store one file per serviceName)
  // Note, we don't need environment when HEAD (TODO model this better)
  def put(serviceName: String, fileName: String, environment: Environment, commitId: String, content: String): Future[Unit] =
    collection
      .replaceOne(
        and(
          equal("serviceName", serviceName),
          equal("environment", environment),
          notEqual("commitId", "HEAD"),
          equal("fileName", fileName)
        ),
        AppConfigBaseRepository.AppConfigBase(
          serviceName = serviceName,
          fileName    = fileName,
          environment = Some(environment),
          commitId    = commitId,
          content     = content
        ),
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def putAllHEAD(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("commitId", "HEAD")).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) =>
               AppConfigBaseRepository.AppConfigBase(
                 serviceName = fileName.stripSuffix(".conf"),
                 fileName    = fileName,
                 environment = None,
                 commitId    = "HEAD",
                 content     = content
               )
             }).toFuture()
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
      .map(_.map(_.content))
}

object AppConfigBaseRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  case class AppConfigBase(
    serviceName : String,
    fileName    : String,
    environment : Option[Environment],
    commitId    : String,
    content     : String
  )

  val mongoFormats: Format[AppConfigBase] = {
    implicit val ef = Environment.format
     ( (__ \ "serviceName").format[String]
     ~ (__ \ "fileName"   ).format[String]
     ~ (__ \ "environment").formatNullable[Environment]
     ~ (__ \ "commitId"   ).format[String]
     ~ (__ \ "content"    ).format[String]
     )(AppConfigBase.apply, unlift(AppConfigBase.unapply))
  }
}
