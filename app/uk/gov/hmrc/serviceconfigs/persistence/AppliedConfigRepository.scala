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

import org.bson.conversions.Bson
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{Indexes, IndexModel}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppliedConfigRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[AppliedConfigRepository.AppliedConfig](
  mongoComponent = mongoComponent,
  collectionName = "appliedConfig",
  domainFormat   = AppliedConfigRepository.AppliedConfig.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("environment", "serviceName", "key")),
                     IndexModel(Indexes.ascending("key", "environment", "serviceName")) // look ups are key first
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format)
) with Transactions {
  import AppliedConfigRepository._

  // irrelevant data is cleaned up by `delete` explicitly
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def put(environment: Environment, serviceName: String, config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _             <- collection.deleteMany(
                           session,
                           and(
                             equal("environment", environment),
                             equal("serviceName", serviceName)
                           )
                         ).toFuture()
        configEntries =  config.map { case (k, v) => AppliedConfig(environment, serviceName = serviceName, key = k, value = v) }.toSeq
        r             <- collection.insertMany(session, configEntries).toFuture()
      } yield ()
    }

  def find(
    key        : String,
    environment: Option[Environment],
    serviceName: Option[String]
  ): Future[Seq[AppliedConfig]] =
    collection.find(
      and(
        equal("key", key),
        environment.fold[Bson](BsonDocument())(e  => equal("environment", e)),
        serviceName.fold[Bson](BsonDocument())(sn => equal("serviceName", sn))
      )
    ).toFuture()

  def delete(environment: Environment, serviceName: String): Future[Unit] =
    collection.deleteMany(
      and(
        equal("environment", environment),
        equal("serviceName", serviceName)
      )
    ).toFuture()
     .map(_ => ())
}

object AppliedConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  case class AppliedConfig(
    environment: Environment,
    serviceName: String,
    key        : String,
    value      : String
  )

  object AppliedConfig {
    val format: Format[AppliedConfig] = {
      implicit val ef = Environment.format
      ( (__ \ "environment").format[Environment]
      ~ (__ \ "serviceName").format[String]
      ~ (__ \ "key"        ).format[String]
      ~ (__ \ "value"      ).format[String].inmap[String](s => s, s => if (s.startsWith("ENC[")) "ENC[...]" else s)
      )(AppliedConfig.apply, unlift(AppliedConfig.unapply))
    }
  }
}
