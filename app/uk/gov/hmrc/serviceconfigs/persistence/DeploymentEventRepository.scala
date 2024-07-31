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

import cats.implicits.toTraverseOps
import org.mongodb.scala.ObservableFuture
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{IndexModel, Indexes, ReplaceOptions, Sorts}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentDateRange, Environment, ServiceName, Version}

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentEventRepository @Inject()(
  val mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[DeploymentEventRepository.DeploymentEvent](
  mongoComponent = mongoComponent,
  collectionName = DeploymentEventRepository.collectionName,
  domainFormat   = DeploymentEventRepository.DeploymentEvent.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("serviceName")),
                     IndexModel(Indexes.ascending("deploymentId")),
                     IndexModel(Indexes.descending("lastUpdated")),
                     IndexModel(Indexes.descending("lastUpdated", "environment", "serviceName"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
):
  import DeploymentEventRepository._

  override lazy val requiresTtlIndex = false

  def put(config: DeploymentEvent): Future[Unit] =
    collection
      .replaceOne(
        filter      = equal("deploymentId", config.deploymentId),
        replacement = config,
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def findAllForService(serviceName: ServiceName, dateRange: DeploymentDateRange): Future[Seq[DeploymentEvent]] =
    def fetchEvents(environment: Environment): Future[Seq[DeploymentEvent]] =
      val filter =
        and(
          equal("serviceName", serviceName),
          equal("environment", environment),
          gt("lastUpdated", dateRange.from),
          lt("lastUpdated", dateRange.to)
        )

      val filterBefore =
        and(
          equal("serviceName", serviceName),
          equal("environment", environment),
          lte("lastUpdated", dateRange.from),
        )

      val filterAfter =
        and(
          equal("serviceName", serviceName),
          equal("environment", environment),
          gte("lastUpdated", dateRange.to),
        )

      for
        inside <- collection.find(filter).sort(Sorts.ascending("lastUpdated")).toFuture()
        before <- collection.find(filterBefore).sort(Sorts.descending("lastUpdated")).headOption()
        after  <- collection.find(filterAfter).sort(Sorts.ascending("lastUpdated")).headOption()
      yield before.toSeq ++ inside ++ after.toSeq

    Environment.values.toList.traverse(fetchEvents).map(_.flatten)

  def findDeploymentEvent(deploymentId: String): Future[Option[DeploymentEvent]] =
    collection
      .find(equal("deploymentId", deploymentId))
      .headOption()

  def findPreviousDeploymentEvent(deploymentEvent: DeploymentEvent): Future[Option[DeploymentEvent]] =
    collection
      .find(
        and(
          equal("serviceName", deploymentEvent.serviceName),
          equal("environment", deploymentEvent.environment),
          lte("lastUpdated", deploymentEvent.time) // TODO rename this to timestamp - it's immutable
        )
      )
      .sort(Sorts.descending("lastUpdated"))
      .skip(1)
      .headOption()

object DeploymentEventRepository:
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "deploymentEvents"

  case class DeploymentEvent(
    serviceName    : ServiceName,
    environment    : Environment,
    version        : Version,
    deploymentId   : String,
    configChanged  : Option[Boolean],
    configId       : Option[String],
    time           : Instant
  )

  object DeploymentEvent:
    val apiFormat: Format[DeploymentEvent] =
      ( (__ \ "serviceName"  ).format[ServiceName](ServiceName.format)
      ~ (__ \ "environment"  ).format[Environment](Environment.format)
      ~ (__ \ "version"      ).format[Version](Version.format)
      ~ (__ \ "deploymentId" ).format[String]
      ~ (__ \ "configChanged").formatNullable[Boolean]
      ~ (__ \ "configId"     ).formatNullable[String]
      ~ (__ \ "lastUpdated"  ).format[Instant]
      )(DeploymentEvent.apply, pt => Tuple.fromProductTyped(pt))

    val mongoFormat: Format[DeploymentEvent] =
      ( (__ \ "serviceName"  ).format[ServiceName](ServiceName.format)
      ~ (__ \ "environment"  ).format[Environment](Environment.format)
      ~ (__ \ "version"      ).format[Version](Version.format)
      ~ (__ \ "deploymentId" ).format[String]
      ~ (__ \ "configChanged").formatNullable[Boolean]
      ~ (__ \ "configId"     ).formatNullable[String]
      ~ (__ \ "lastUpdated"  ).format[Instant](MongoJavatimeFormats.instantFormat)
      )(DeploymentEvent.apply, pt => Tuple.fromProductTyped(pt))
