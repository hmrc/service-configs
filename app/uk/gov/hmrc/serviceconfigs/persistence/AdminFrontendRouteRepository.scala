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

import com.mongodb.client.model.Indexes

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.model.Filters.*
import org.mongodb.scala.model.{Filters, IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{AdminFrontendRoute, ServiceName}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AdminFrontendRouteRepository @Inject()(
  mongoComponent: MongoComponent
)(using ec: ExecutionContext
) extends PlayMongoRepository[AdminFrontendRoute](
  mongoComponent = mongoComponent,
  collectionName = "adminFrontendRoutes",
  domainFormat   = AdminFrontendRoute.format,
  indexes        = Seq(
                     IndexModel(
                       Indexes.ascending("service", "route")
                     , IndexOptions().unique(true)
                     )
                   ),
  replaceIndexes = true,
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
):
  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def findRoutes(serviceName: Option[ServiceName] = None): Future[Seq[AdminFrontendRoute]] =
    collection
      .find(serviceName.fold(Filters.empty)(Filters.equal("service", _)))
      .toFuture()
  
  def putAll(routes: Seq[AdminFrontendRoute]): Future[Unit] =
    MongoUtils.replace[AdminFrontendRoute](
      collection    = collection,
      newVals       = routes,
      compareById   = (a, b) => a.serviceName == b.serviceName && a.route == b.route,
      filterById    = entry =>
                        and(
                          equal("service", entry.serviceName),
                          equal("route", entry.route)
                        )
    )
