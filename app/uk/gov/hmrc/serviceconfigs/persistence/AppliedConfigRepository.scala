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
import org.mongodb.scala.model.{Aggregates, Filters, Indexes, IndexModel}
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
                     IndexModel(Indexes.ascending("onlyReference")),
                     IndexModel(Indexes.ascending("serviceName", "key")),
                     IndexModel(Indexes.ascending("key"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) with Transactions {
  import AppliedConfigRepository._

  // irrelevant data is cleaned up by `delete` explicitly
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def put(serviceName: ServiceName, environment: Environment, config: Map[String, (String, String)]): Future[Unit] =
    for {
      old     <- collection.find(Filters.equal("serviceName", serviceName)).toFuture()
      updated =  old.map(x => x.copy(environments = config.get(x.key) match {
                    case Some((v, source)) => x.environments ++ Map(environment -> EnvironmentData(value = v, source = source))
                    case None              => x.environments - environment
                  }))
      missing =  config
                  .filterNot { case (k, _) => old.exists(_.key == k) }
                  .map { case (k, (v, source)) => AppliedConfig(serviceName, k, Map(environment -> EnvironmentData(value = v, source = source)), onlyReference = false) }
      entries =  (updated ++ missing)
                    .filter(_.environments.nonEmpty)
                    .map(x => x.copy(onlyReference = !x.environments.exists(_._2.source != "referenceConf")))
      _       <- withSessionAndTransaction { session =>
                    for {
                      _ <- collection.deleteMany(session, Filters.equal("serviceName", serviceName)).toFuture()
                      _ <- if (entries.nonEmpty) collection.insertMany(session, entries).toFuture()
                           else                  Future.unit
                    } yield ()
                 }
    } yield ()

  def delete(serviceName: ServiceName, environment: Environment): Future[Unit] =
    put(serviceName, environment, Map.empty)

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

  case class EnvironmentData(
    value : String
  , source: String
  )

  case class AppliedConfig(
    serviceName  : ServiceName
  , key          : String
  , environments : Map[Environment, EnvironmentData]
  , onlyReference: Boolean
  )

  object AppliedConfig {
    val format: Format[AppliedConfig] = {
      implicit val snf = ServiceName.format
      implicit val edf: Format[EnvironmentData] =
        ( (__ \ "value" ).format[String].inmap[String](s => s, s => if (s.startsWith("ENC[")) "ENC[...]" else s)
        ~ (__ \ "source").format[String]
        )(EnvironmentData.apply, unlift(EnvironmentData.unapply))

      implicit val readsEnvMap: Format[Map[Environment, EnvironmentData]] =
        Format(
          Reads
            .of[Map[String, EnvironmentData]]
            .map(_.map { case (k, v) => (Environment.parse(k).getOrElse(sys.error(s"Invalid Environment: $k")), v) })
        , Writes
            .apply { xs => Json.toJson(xs.map { case (k, v) => k.asString -> v }) }
        )

      ( (__ \ "serviceName"  ).format[ServiceName]
      ~ (__ \ "key"          ).format[String]
      ~ (__ \ "environments" ).format[Map[Environment, EnvironmentData]]
      ~ (__ \ "onlyReference").format[Boolean]
      )(AppliedConfig.apply, unlift(AppliedConfig.unapply))
    }
  }
}
