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
import org.mongodb.scala.ObservableFuture
import org.mongodb.scala.model.{IndexModel, IndexOptions, Filters}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{UpscanConfig, ServiceName}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpscanConfigRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[UpscanConfig](
  mongoComponent = mongoComponent,
  collectionName = "upscanConfig",
  domainFormat   = UpscanConfig.format,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx"))
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
):
  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def findByService(serviceName: ServiceName): Future[Seq[UpscanConfig]] =
    collection
      .find(Filters.equal("service", serviceName))
      .toFuture()

  def putAll(upscanConfigs: Seq[UpscanConfig]): Future[Unit] =
    MongoUtils.replace[UpscanConfig](
      collection    = collection,
      newVals       = upscanConfigs,
      compareById   = (a, b) => a.serviceName == b.serviceName,
      filterById    = entry => Filters.equal("service", entry.serviceName)
    )
