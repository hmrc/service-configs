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
import org.mongodb.scala.model.{Indexes, IndexModel}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** This stores the latest (HEAD) files and their content against the repository name.
  */
@Singleton
class LatestConfigRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[LatestConfigRepository.LatestConfig](
  mongoComponent = mongoComponent,
  collectionName = LatestConfigRepository.collectionName,
  domainFormat   = LatestConfigRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("repoName", "fileName"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format)
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def find(repoName: String, fileName: String): Future[Option[String]] =
    collection
      .find(
        and(
          equal("repoName", repoName),
          equal("fileName", fileName)
        )
      )
      .headOption()
      .map(_.map(_.content))

  def put(repoName: String)(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("repoName", repoName)).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) =>
               LatestConfigRepository.LatestConfig(
                 repoName    = repoName,
                 fileName    = fileName,
                 content     = content
               )
             }).toFuture()
      } yield ()
    }
}

object LatestConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "appConfig"

  case class LatestConfig(
    repoName    : String,
    fileName    : String,
    content     : String
  )

  val mongoFormats: Format[LatestConfig] =
    ( (__ \ "repoName"   ).format[String]
    ~ (__ \ "fileName"   ).format[String]
    ~ (__ \ "content"    ).format[String]
    )(LatestConfig. apply, unlift(LatestConfig.unapply))
}
