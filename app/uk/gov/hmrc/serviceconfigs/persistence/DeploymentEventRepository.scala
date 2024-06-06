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

import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.{IndexModel, Indexes, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, Version}

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentEventRepository @Inject()(
  val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[DeploymentEventRepository.DeploymentEvent](
  mongoComponent = mongoComponent,
  collectionName = DeploymentEventRepository.collectionName,
  domainFormat   = DeploymentEventRepository.DeploymentEvent.mongoFormats,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("deploymentId"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) {

  override lazy val requiresTtlIndex = false

  def put(config: DeploymentEventRepository.DeploymentEvent): Future[Unit] =
    collection
      .replaceOne(
        filter      = equal("deploymentId", config.deploymentId),
        replacement = config,
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def find(deploymentId: String): Future[Option[DeploymentEventRepository.DeploymentEvent]] =
    collection
      .find(
        equal("deploymentId", deploymentId)
      )
      .headOption()

  def delete(deploymentId: String): Future[Unit] =
    collection
      .deleteOne(
        equal("deploymentId", deploymentId)
      )
      .toFuture()
      .map(_ => ())
}

object DeploymentEventRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "deploymentEvents"

  case class DeploymentEvent(
    serviceName    : ServiceName,
    environment    : Environment,
    version        : Version,
    deploymentId   : String,
    configChanged  : Boolean,
    configId       : String,
    lastUpdated    : Instant
  )

  object DeploymentEvent {
    implicit val mongoFormats: Format[DeploymentEvent] = {
      implicit val instantFormat = MongoJavatimeFormats.instantFormat
      implicit val ef = Environment.format
      implicit val snf = ServiceName.format
      implicit val vf = Version.format
      ((__ \ "serviceName").format[ServiceName]
        ~ (__ \ "environment").format[Environment]
        ~ (__ \ "version").format[Version]
        ~ (__ \ "deploymentId").format[String]
        ~ (__ \ "configChanged").format[Boolean]
        ~ (__ \ "configId").format[String]
        ~ (__ \ "lastUpdated").format[Instant]
        )(DeploymentEvent.apply, unlift(DeploymentEvent.unapply))
    }

    def apply(
      serviceName   : ServiceName,
      environment   : Environment,
      version       : Version,
      deploymentId  : String,
      configChanged : Boolean,
      configId      : String,
      lastUpdated   : Instant
    ): DeploymentEvent = {
      //Artificial deploymentId to be used for old deployments until B&D can provide us with a unique deploymentId
      val uniqueDeploymentId = if(deploymentId.startsWith("arn")) s"${serviceName.asString}-${environment.asString}-${version}-${lastUpdated}" else deploymentId
      new DeploymentEvent(serviceName, environment, version, uniqueDeploymentId, configChanged, configId, lastUpdated)
    }
  }
}
