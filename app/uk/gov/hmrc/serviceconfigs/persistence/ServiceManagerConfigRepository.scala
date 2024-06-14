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
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.ServiceName

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceManagerConfigRepository @Inject()(
  mongoComponent: MongoComponent
)(using ec: ExecutionContext
) extends PlayMongoRepository[ServiceManagerConfigRepository.ServiceManagerConfig](
  mongoComponent = mongoComponent,
  collectionName = "serviceManagerConfig",
  domainFormat   = ServiceManagerConfigRepository.ServiceManagerConfig.format,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx"))
                   ),
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
) {
  import ServiceManagerConfigRepository._

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def findByService(serviceName: ServiceName): Future[Option[ServiceManagerConfig]] =
    collection
      .find(equal("service", serviceName))
      .headOption()

  def putAll(jobs: Seq[ServiceManagerConfig]): Future[Unit] =
    MongoUtils.replace[ServiceManagerConfig](
      collection    = collection,
      newVals       = jobs,
      compareById   = (a, b) => a.serviceName == b.serviceName,
      filterById    = entry => equal("service", entry.serviceName)
    )
}

object ServiceManagerConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  case class ServiceManagerConfig(
    serviceName: ServiceName,
    location   : String
  )

  object ServiceManagerConfig {
    val format: Format[ServiceManagerConfig] = {
      ( (__ \ "service" ).format[ServiceName](ServiceName.format)
      ~ (__ \ "location").format[String]
      )(ServiceManagerConfig.apply, pt => Tuple.fromProductTyped(pt))
    }
  }
}
