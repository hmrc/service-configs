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

import org.mongodb.scala.model.{DeleteOneModel, Indexes, IndexModel, ReplaceOptions, ReplaceOneModel}
import org.mongodb.scala.model.Filters.{and, equal}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** This stores the latest (HEAD) files and their content against the repository name.
  */
@Singleton
class LatestConfigRepository @Inject()(
  mongoComponent: MongoComponent
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
) {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

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
    for {
      toUpdate    <- Future.successful(config.toSeq.map { case (fileName, content) =>
                       LatestConfigRepository.LatestConfig(
                         repoName    = repoName,
                         fileName    = fileName,
                         content     = content
                       )
                     })
      old         <- collection.find(equal("repoName", repoName)).toFuture()
      toDelete    =  old.filterNot(c => config.contains(c.fileName))
      bulkUpdates =  toUpdate
                       .filterNot(old.contains) // we could use update rather than replace to avoid unnecessary modifications, but filtering out
                                                // those that have not changed here is easier
                       .map(entry =>
                         ReplaceOneModel(
                           and(
                             equal("repoName", repoName),
                             equal("fileName", entry.fileName)
                           ),
                           entry,
                           ReplaceOptions().upsert(true)
                         )
                       ) ++
                       toDelete.map(entry =>
                         DeleteOneModel(
                           and(
                             equal("repoName", repoName),
                             equal("fileName", entry.fileName)
                           )
                         )
                       )
       _          <- if (bulkUpdates.isEmpty) Future.unit
                     else collection.bulkWrite(bulkUpdates).toFuture().map(_=> ())
    } yield ()
}

object LatestConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "latestConfig"

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
