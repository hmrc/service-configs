/*
 * Copyright 2019 HM Revenue & Customs
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

import com.google.inject.{Inject, Singleton}
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.PlayMongoCollection
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, MongoSlugInfoFormats}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyConfigRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoCollection(
      mongoComponent = mongoComponent,
      "dependencyConfigs",
      MongoSlugInfoFormats.dcFormat,
      Seq(
        IndexModel(
          Indexes.ascending("group", "artefact", "version"),
          IndexOptions().unique(true).name("dependencyConfigUniqueIdx"))
      )
    ) {

  def add(dependencyConfig: DependencyConfig): Future[Boolean] = {

    val filter: Bson = and(
      equal("group", dependencyConfig.group),
      equal("artefact", dependencyConfig.artefact),
      equal("version", dependencyConfig.version))

    val options = FindOneAndReplaceOptions().upsert(true)
    collection
      .findOneAndReplace(
        filter      = filter,
        replacement = dependencyConfig,
        options     = options
      )
      .toFutureOption()
      .map(_.isDefined)
  }

  def getAllEntries: Future[Seq[DependencyConfig]] = collection.find().toFuture()

  def clearAllData: Future[Boolean] = collection.drop().toFutureOption().map(_.isDefined)

  def getDependencyConfig(group: String, artefact: String, version: String): Future[Option[DependencyConfig]] =
    collection
      .find(and(equal("group", group), equal("artefact", artefact), equal("version", version)))
      .first()
      .toFutureOption()

}
