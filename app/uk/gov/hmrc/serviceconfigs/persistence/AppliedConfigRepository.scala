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

import play.api.Configuration
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Accumulators, Aggregates, Filters, Indexes, IndexModel, Sorts}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{Environment, FilterType, ServiceName}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppliedConfigRepository @Inject()(
  configuration: Configuration,
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[AppliedConfigRepository.AppliedConfig](
  mongoComponent = mongoComponent,
  collectionName = "appliedConfig",
  domainFormat   = AppliedConfigRepository.AppliedConfig.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("serviceName", "environment", "key")),
                     IndexModel(Indexes.ascending("environment", "key")),
                     IndexModel(Indexes.ascending("key"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) with Transactions {
  import AppliedConfigRepository._

  // irrelevant data is cleaned up by `delete` explicitly
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def put(serviceName: ServiceName, environment: Environment, config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _             <- collection.deleteMany(
                           session,
                           Filters.and(
                             Filters.equal("serviceName", serviceName),
                             Filters.equal("environment", environment)
                           )
                         ).toFuture()
        configEntries =  config.map { case (k, v) => AppliedConfig(serviceName = serviceName, environment = environment, key = k, value = v) }.toSeq
        _             <- collection.insertMany(session, configEntries).toFuture()
      } yield ()
    }

  private def maxSearchLimit = configuration.get[Int]("config-search.max-limit")

  private def toFilter(field: String, fieldValue: Option[String], filterType: FilterType) =
    (fieldValue, filterType) match {
      case (Some(v), FilterType.Contains)       => Filters.regex(field, java.util.regex.Pattern.quote(v).r)
      case (Some(v), FilterType.DoesNotContain) => Filters.not(Filters.regex(field, java.util.regex.Pattern.quote(v).r))
      case (Some(v), FilterType.EqualTo)        => Filters.equal(field, v)
      case (Some(v), FilterType.NotEqualTo)     => Filters.not(Filters.equal(field, v))
      case (_      , FilterType.IsEmpty)        => Filters.equal(field, "")
      case _                                    => Filters.empty()
    }

  def search(
    serviceNames   : Option[Seq[ServiceName]],
    environment    : Seq[Environment],
    key            : Option[String],
    keyFilterType  : FilterType,
    value          : Option[String],
    valueFilterType: FilterType
  ): Future[Seq[AppliedConfig]] =
    collection
      .aggregate(Seq(
        Aggregates.`match`(
          Filters.and(
            serviceNames.fold(Filters.empty())(xs => Filters.in("serviceName", xs.map(_.asString): _*)),
            if (environment.isEmpty) Filters.empty() else Filters.in("environment", environment.map(_.asString): _*),
            toFilter("key", key, keyFilterType)
          )
        ),
        Aggregates.group(
          BsonDocument("serviceName" -> s"$$serviceName", "key" -> s"$$key"),
          Accumulators.push("results", "$$ROOT")
        ),
        Aggregates.`match`(toFilter("results.value", value, valueFilterType)),
        Aggregates.unwind("$results"),
        Aggregates.replaceRoot("$results"),
        Aggregates.limit(maxSearchLimit + 1) // Controller sets request to Forbidden if over maxSearchLimit
      ))
     .toFuture()

  def findConfigKeys(serviceNames: Option[Seq[ServiceName]]): Future[Seq[String]] =
    serviceNames match {
      case Some(s) => collection
                        .aggregate[BsonDocument](Seq(
                          Aggregates.`match`(Filters.in("serviceName", s.map(_.asString): _*))
                        , Aggregates.group("$key")
                        , Aggregates.sort(Sorts.ascending("_id"))
                        ))
                        .toFuture()
                        .map(_.map(_.getString("_id").getValue))
      case None    => collection
                        .distinct[String]("key")
                        .toFuture()
                        .map(_.sorted)
    }

  def delete(serviceName: ServiceName, environment: Environment): Future[Unit] =
    collection.deleteMany(
      Filters.and(
        Filters.equal("serviceName", serviceName),
        Filters.equal("environment", environment)
      )
    ).toFuture()
     .map(_ => ())
}

object AppliedConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  case class AppliedConfig(
    serviceName: ServiceName,
    environment: Environment,
    key        : String,
    value      : String
  )

  object AppliedConfig {
    val format: Format[AppliedConfig] = {
      implicit val ef  = Environment.format
      implicit val snf = ServiceName.format
      ( (__ \ "serviceName").format[ServiceName]
      ~ (__ \ "environment").format[Environment]
      ~ (__ \ "key"        ).format[String]
      ~ (__ \ "value"      ).format[String].inmap[String](s => s, s => if (s.startsWith("ENC[")) "ENC[...]" else s)
      )(AppliedConfig.apply, unlift(AppliedConfig.unapply))
    }
  }
}
