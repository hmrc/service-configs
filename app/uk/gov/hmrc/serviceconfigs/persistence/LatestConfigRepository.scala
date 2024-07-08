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

import org.mongodb.scala.ObservableFuture
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{IndexModel, Indexes, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{Environment, FileName, Content}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** This stores the latest (HEAD) files and their content against the repository name.
  */
@Singleton
class LatestConfigRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[LatestConfigRepository.LatestConfig](
  mongoComponent = mongoComponent,
  collectionName = LatestConfigRepository.collectionName,
  domainFormat   = LatestConfigRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(
                       Indexes.ascending("repoName", "fileName"),
                       IndexOptions().unique(true)
                     )
                   ),
  replaceIndexes = true,
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+
                   Codecs.playFormatCodec(FileName.format)        :+
                   Codecs.playFormatCodec(Content.format)
):
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
      .map(_.map(_.content.asString))

  def findAll(repoName: String): Future[Seq[LatestConfigRepository.LatestConfig]] =
    collection
      .find(equal("repoName", repoName))
      .toFuture()

  def put(repoName: String)(config: Map[FileName, Content]): Future[Unit] =
    MongoUtils.replace[LatestConfigRepository.LatestConfig](
      collection    = collection,
      newVals       = config.toSeq.map:
                        case (fileName, content) =>
                          LatestConfigRepository.LatestConfig(
                            repoName = repoName,
                            fileName = fileName,
                            content  = content
                          )
                      ,
      oldValsFilter = equal("repoName", repoName),
      compareById   = (a, b) =>
                        a.repoName == b.repoName &&
                        a.fileName == b.fileName,
      filterById    = entry =>
                        and(
                          equal("repoName", repoName),
                          equal("fileName", entry.fileName)
                        )
    )

object LatestConfigRepository:
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "latestConfig"

  case class LatestConfig(
    repoName: String,
    fileName: FileName,
    content : Content
  )

  val mongoFormats: Format[LatestConfig] =
    ( (__ \ "repoName").format[String]
    ~ (__ \ "fileName").format[FileName](FileName.format)
    ~ (__ \ "content" ).format[Content](Content.format)
    )(LatestConfig.apply, pt => Tuple.fromProductTyped(pt))
