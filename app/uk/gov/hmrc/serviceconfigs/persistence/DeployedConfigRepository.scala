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
import org.mongodb.scala.model.{IndexModel, Indexes, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeployedConfigRepository @Inject()(
  val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[DeployedConfigRepository.DeployedConfig](
  mongoComponent = mongoComponent,
  collectionName = DeployedConfigRepository.collectionName,
  domainFormat   = DeployedConfigRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("serviceName", "environment")),
                     IndexModel(Indexes.hashed("configId"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  def put(config: DeployedConfigRepository.DeployedConfig): Future[Unit] =
    collection
      .replaceOne(
        filter      = and(
                        equal("serviceName", config.serviceName),
                        equal("environment", config.environment)
                      ),
        replacement = config,
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def find(serviceName: ServiceName, environment: Environment): Future[Option[DeployedConfigRepository.DeployedConfig]] =
    collection
      .find(
        and(
          equal("serviceName", serviceName),
          equal("environment", environment)
        )
      )
      .headOption()

  def delete(serviceName: ServiceName, environment: Environment): Future[Unit] =
    collection
      .deleteOne(
        and(
          equal("serviceName", serviceName),
          equal("environment", environment)
        )
      )
      .toFuture()
      .map(_ => ())
}

object DeployedConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "deployedConfig"

  case class DeployedConfig(
    serviceName    : ServiceName,
    environment    : Environment,
    deploymentId   : String,
    configId       : String,
    appConfigBase  : Option[String],
    appConfigCommon: Option[String],
    appConfigEnv   : Option[String],
    lastUpdated    : Instant
  )

  val mongoFormats: Format[DeployedConfig] = {
    implicit val instantFormat = MongoJavatimeFormats.instantFormat
    implicit val ef  = Environment.format
    implicit val snf = ServiceName.format
    ( (__ \ "serviceName"    ).format[ServiceName]
    ~ (__ \ "environment"    ).format[Environment]
    ~ (__ \ "deploymentId"   ).format[String]
    ~ (__ \ "configId"       ).formatWithDefault[String]("")
    ~ (__ \ "appConfigBase"  ).formatNullable[String]
    ~ (__ \ "appConfigCommon").formatNullable[String]
    ~ (__ \ "appConfigEnv"   ).formatNullable[String]
    ~ (__ \ "lastUpdated"    ).format[Instant]
    )(DeployedConfig.apply, pt => Tuple.fromProductTyped(pt))
  }
}
