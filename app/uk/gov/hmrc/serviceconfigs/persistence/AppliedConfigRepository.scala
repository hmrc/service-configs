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

import cats.implicits._
import play.api.Configuration
import org.mongodb.scala.ObservableFuture
import org.mongodb.scala.model.{Aggregates, DeleteOneModel, Filters, Indexes, IndexModel, ReplaceOptions, ReplaceOneModel}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{Environment, FilterType, ServiceName}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.RenderedConfigSourceValue

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppliedConfigRepository @Inject()(
  configuration : Configuration,
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[AppliedConfigRepository.AppliedConfig](
  mongoComponent = mongoComponent,
  collectionName = "appliedConfig",
  domainFormat   = AppliedConfigRepository.AppliedConfig.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("onlyReference")),
                     IndexModel(Indexes.ascending("serviceName", "key")),
                     IndexModel(Indexes.ascending("key"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) {
  import AppliedConfigRepository._

  // irrelevant data is cleaned up by `delete` explicitly
  override lazy val requiresTtlIndex = false

  def put(serviceName: ServiceName, environment: Environment, config: Map[String, RenderedConfigSourceValue]): Future[Unit] =
    for {
      old     <- collection.find(Filters.equal("serviceName", serviceName)).toFuture()
      updated =  old.map(x => x.copy(environments = config.get(x.key) match {
                    case Some(configSourceValue) => x.environments ++ Map(environment -> configSourceValue)
                    case None                    => x.environments - environment
                  }))
      newConfig =  config
                  .filterNot { case (k, _) => old.exists(_.key == k) }
                  .map { case (k, configSourceValue) => AppliedConfig(serviceName, k, Map(environment -> configSourceValue), onlyReference = false) }
      entries =  (updated ++ newConfig)
                   .map(x => x.copy(onlyReference = !x.environments.exists(_._2.source != "referenceConf")))
      (toUpdate, toDelete) = entries.partition(_.environments.nonEmpty)
      bulkUpdates = toUpdate
                     .filterNot(old.contains) // we could use update rather than replace to avoid unnecessary modifications, but filtering out
                                              // those that have not changed here is easier
                     .map(entry =>
                       ReplaceOneModel(
                         Filters.and(
                           Filters.equal("serviceName", serviceName),
                           Filters.equal("key"        , entry.key)
                         ),
                         entry,
                         ReplaceOptions().upsert(true)
                       )
                     ) ++
                     toDelete.map(entry =>
                       DeleteOneModel(
                         Filters.and(
                           Filters.equal("serviceName", serviceName),
                           Filters.equal("key"        , entry.key)
                         )
                       )
                     )
       _      <- if (bulkUpdates.isEmpty) Future.unit
                 else collection.bulkWrite(bulkUpdates).toFuture().map(_=> ())
    } yield ()

  def delete(serviceName: ServiceName, environment: Environment): Future[Unit] =
    put(serviceName, environment, Map.empty)

  private def maxSearchLimit = configuration.get[Int]("config-search.max-limit")

  import java.util.regex.Pattern
  private def toFilter(field: String, fieldValue: Option[String], filterType: FilterType) =
    (fieldValue, filterType) match {
      case (Some(v), FilterType.Contains)                 => Filters.regex(field, Pattern.quote(v).r)
      case (Some(v), FilterType.ContainsIgnoreCase)       => Filters.regex(field, s"(?i)${Pattern.quote(v)}".r)
      case (Some(v), FilterType.DoesNotContain)           => Filters.not(Filters.regex(field, Pattern.quote(v).r))
      case (Some(v), FilterType.DoesNotContainIgnoreCase) => Filters.not(Filters.regex(field, s"(?i)${Pattern.quote(v)}".r))
      case (Some(v), FilterType.EqualTo)                  => Filters.equal(field, v)
      case (Some(v), FilterType.EqualToIgnoreCase)        => Filters.regex(field, s"(?i)^${Pattern.quote(v)}$$".r)
      case (Some(v), FilterType.NotEqualTo)               => Filters.notEqual(field, v)
      case (Some(v), FilterType.NotEqualToIgnoreCase)     => Filters.not(Filters.regex(field, s"(?i)^${Pattern.quote(v)}$$".r))
      case (_      , FilterType.IsEmpty)                  => Filters.equal(field, "")
      case _                                              => Filters.empty()
    }

  def search(
    serviceNames   : Option[Seq[ServiceName]],
    environments   : Seq[Environment],
    key            : Option[String],
    keyFilterType  : FilterType,
    value          : Option[String],
    valueFilterType: FilterType
  ): Future[Seq[AppliedConfig]] =
    collection
      .aggregate(Seq(
        Aggregates.`match`(
          Filters.and(
            Filters.equal("onlyReference", false),
            serviceNames.fold(Filters.empty())(xs => Filters.in("serviceName", xs.map(_.asString): _*)),
            toFilter("key", key, keyFilterType),
            Filters.or(
              (if (environments.isEmpty) Environment.values else environments).map { e =>
                Filters.and(
                  Filters.notEqual(s"environments.${e.asString}", null),
                  toFilter(s"environments.${e.asString}.value", value, valueFilterType)
                )
              }: _*
            )
          )
        ),
        Aggregates.limit(maxSearchLimit + 1) // Controller sets request to Forbidden if over maxSearchLimit
      ))
     .toFuture()

  def findConfigKeys(serviceNames: Option[Seq[ServiceName]]): Future[Seq[String]] =
    collection
      .distinct[String]("key", Filters.and(Filters.equal("onlyReference", false), serviceNames.fold(Filters.empty())(s => Filters.in("serviceName", s.map(_.asString): _*))))
      .toFuture()
      .map(_.sorted)
}

object AppliedConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, Json, Reads, Writes, __}

  case class AppliedConfig(
    serviceName  : ServiceName
  , key          : String
  , environments : Map[Environment, RenderedConfigSourceValue]
  , onlyReference: Boolean
  )

  object AppliedConfig {
    val format: Format[AppliedConfig] = {
      implicit val snf = ServiceName.format
      implicit val rcsvf: Format[RenderedConfigSourceValue] =
        ( (__ \ "source"   ).format[String]
        ~ (__ \ "sourceUrl").formatNullable[String]
        ~ (__ \ "value"    ).format[String]
        )(RenderedConfigSourceValue.apply, pt => Tuple.fromProductTyped(pt))

      implicit val readsEnvMap: Format[Map[Environment, RenderedConfigSourceValue]] =
        Format(
          Reads
            .of[Map[String, RenderedConfigSourceValue]]
            .map(_.map { case (k, v) => (Environment.parse(k).getOrElse(sys.error(s"Invalid Environment: $k")), v) })
        , Writes
            .apply { xs => Json.toJson(xs.map { case (k, v) => k.asString -> v }) }
        )

      ( (__ \ "serviceName"  ).format[ServiceName]
      ~ (__ \ "key"          ).format[String]
      ~ (__ \ "environments" ).format[Map[Environment, RenderedConfigSourceValue]]
      ~ (__ \ "onlyReference").format[Boolean]
      )(AppliedConfig.apply, pt => Tuple.fromProductTyped(pt))
    }
  }
}
