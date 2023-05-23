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

import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{Indexes, IndexModel, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.Environment

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
                     IndexModel(Indexes.hashed("deploymentId"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format)
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

  def find(serviceName: String, environment: Environment): Future[Option[DeployedConfigRepository.DeployedConfig]] =
    collection
      .find(
        and(
          equal("serviceName", serviceName),
          equal("environment", environment)
        )
      )
      .headOption()

  def delete(serviceName: String, environment: Environment): Future[Unit] =
    collection.deleteOne(
      and(
        equal("serviceName", serviceName),
        equal("environment", environment)
      )
    ).toFuture()
     .map(_ => ())

  def hasProcessed(deploymentId: String): Future[Boolean] =
    collection.find(equal("deploymentId", deploymentId))
      .headOption()
      .map(_.isDefined)
}

object DeployedConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "appConfig"

  case class DeployedConfig(
    serviceName    : String,
    environment    : Environment,
    deploymentId   : String,
    appConfigBase  : Option[String], // TODO also keep commitId?
    appConfigCommon: Option[String],
    appConfigEnv   : Option[String]
  )

  val mongoFormats: Format[DeployedConfig] = {
    implicit val ef = Environment.format
    ( (__ \ "serviceName"    ).format[String]
    ~ (__ \ "environment"    ).format[Environment]
    ~ (__ \ "deploymentId"   ).format[String]
    ~ (__ \ "appConfigBase"  ).formatNullable[String]
    ~ (__ \ "appConfigCommon").formatNullable[String]
    ~ (__ \ "appConfigEnv"   ).formatNullable[String]
    )(DeployedConfig. apply, unlift(DeployedConfig.unapply))
  }
}