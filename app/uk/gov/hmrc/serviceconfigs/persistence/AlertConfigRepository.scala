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
import org.mongodb.scala.model.{IndexModel, IndexOptions, Filters, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{AlertEnvironmentHandler, ServiceName}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AlertEnvironmentHandlerRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[AlertEnvironmentHandler](
  mongoComponent = mongoComponent,
  collectionName = "alertEnvironmentHandlers",
  domainFormat   = AlertEnvironmentHandler.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("serviceName"),
                     IndexOptions().unique(true))
                   ),
  replaceIndexes = true,
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
):
  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def putAll(alertEnvironmentHandlers: Seq[AlertEnvironmentHandler]): Future[Unit] =
    MongoUtils.replace[AlertEnvironmentHandler](
      collection    = collection,
      newVals       = alertEnvironmentHandlers,
      compareById   = (a, b) => a.serviceName == b.serviceName,
      filterById    = entry => Filters.equal("serviceName", entry.serviceName)
    )

  def findByServiceName(serviceName: ServiceName): Future[Option[AlertEnvironmentHandler]] =
    collection
      .find(Filters.equal("serviceName", serviceName))
      .headOption()

  def findAll(): Future[Seq[AlertEnvironmentHandler]] =
    collection
      .find()
      .toFuture()
      .map(_.toList)
