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
import play.api.libs.json.Json
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.serviceconfigs.model.{DependencyConfig, MongoSlugInfoFormats}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DependencyConfigRepository @Inject()(mongo: ReactiveMongoComponent)
    extends ReactiveRepository[DependencyConfig, BSONObjectID](
      collectionName = "dependencyConfigs",
      mongo = mongo.mongoConnector.db,
      domainFormat = MongoSlugInfoFormats.dcFormat
    ) {

  implicit val mf = MongoSlugInfoFormats.dcFormat
  import ExecutionContext.Implicits.global

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq(
          "group" -> IndexType.Ascending,
          "artefact" -> IndexType.Ascending,
          "version" -> IndexType.Ascending
        ),
        name = Some("dependencyConfigUniqueIdx"),
        unique = true
      )
    )

  def add(dependencyConfig: DependencyConfig): Future[Boolean] =
    collection
      .update(ordered = false)
      .one(
        q = Json.obj(
          "group" -> Json.toJson(dependencyConfig.group),
          "artefact" -> Json.toJson(dependencyConfig.artefact),
          "version" -> Json.toJson(dependencyConfig.version)
        ),
        u = dependencyConfig,
        upsert = true
      )
      .map(_.ok)

  def getAllEntries: Future[Seq[DependencyConfig]] =
    findAll()

  def clearAllData: Future[Boolean] =
    super.removeAll().map(_.ok)

  def getDependencyConfig(group: String,
                          artefact: String,
                          version: String): Future[Option[DependencyConfig]] =
    find("group" -> group, "artefact" -> artefact, "version" -> version)
      .map(_.headOption)
}
