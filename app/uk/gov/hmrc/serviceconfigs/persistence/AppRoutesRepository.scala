/*
 * Copyright 2025 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.model.{IndexModel, Indexes, ReplaceOptions}
import org.mongodb.scala.model.Filters.{and, equal}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{AppRoutes, ServiceName, Version}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppRoutesRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[AppRoutes](
  mongoComponent = mongoComponent,
  collectionName = "appRoutes",
  domainFormat   = AppRoutes.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("service", "version"))
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format), Codecs.playFormatCodec(Version.format))
):

  override lazy val requiresTtlIndex: Boolean = false // records are deleted when the slug/version is deleted from artifactory

  def put(routes: AppRoutes): Future[Unit] =
    collection
      .replaceOne(
        filter      = and(
                        equal("service", routes.service),
                        equal("version", routes.version)
                      ),
        replacement = routes,
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def find(name: ServiceName, version: Version): Future[Option[AppRoutes]] =
    collection
      .find(
        filter = and(
          equal("service", name),
          equal("version", version)
        )
      )
      .headOption()

  def delete(name: ServiceName, version: Version): Future[Unit] =
    collection
      .deleteOne(
          and(
            equal("service", name),
            equal("version", version)
          )
        )
      .toFuture()
      .map(_ => ())
