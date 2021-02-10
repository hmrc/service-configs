/*
 * Copyright 2021 HM Revenue & Customs
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
import com.mongodb.BasicDBObject
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, MongoSlugInfoFormats}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyConfigRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoRepository(
      mongoComponent = mongoComponent,
      collectionName = "dependencyConfigs",
      domainFormat   = MongoSlugInfoFormats.dcFormat,
      indexes = Seq(
        IndexModel(
          Indexes.ascending("group", "artefact", "version"),
          IndexOptions().unique(true).name("dependencyConfigUniqueIdx"))
      )
    ) {

  def add(dependencyConfig: DependencyConfig): Future[Unit] =
    collection
      .findOneAndReplace(
        filter = and(
          equal("group", dependencyConfig.group),
          equal("artefact", dependencyConfig.artefact),
          equal("version", dependencyConfig.version)),
        replacement = dependencyConfig,
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())

  def getAllEntries: Future[Seq[DependencyConfig]] =
    collection.find().toFuture()

  def clearAllData: Future[Boolean] = collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())

  def getDependencyConfig(group: String, artefact: String, version: String): Future[Option[DependencyConfig]] =
    collection
      .find(and(equal("group", group), equal("artefact", artefact), equal("version", version)))
      .first()
      .toFutureOption()

}
