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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.{Filters, Indexes, IndexModel}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.service.{ConfigWarning, ConfigService}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.RenderedConfigSourceValue

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigWarningRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[ConfigWarning](
  mongoComponent = mongoComponent,
  collectionName = "configWarnings",
  domainFormat   = ConfigWarningRepository.configWarningFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("environment")),
                     IndexModel(Indexes.ascending("serviceName"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def find(environments: Option[Seq[Environment]], serviceNames: Option[Seq[ServiceName]]): Future[Seq[ConfigWarning]] =
    collection.find(
      Filters.and(
        serviceNames.fold(Filters.empty())(sn => Filters.in("serviceName", sn: _*)),
        environments.fold(Filters.empty())(e  => Filters.in("environment", e: _*))
      )
    ).toFuture()

  def putAll(warnings: Seq[ConfigWarning]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        r <- collection.insertMany(session, warnings).toFuture()
      } yield ()
    }
}

object ConfigWarningRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val configWarningFormat: Format[ConfigWarning] = {
    implicit val ef  = Environment.format
    implicit val snf = ServiceName.format

    implicit val rcsvf =
      ( (__ \ "source"   ).format[String]
      ~ (__ \ "sourceUrl").formatNullable[String]
      ~ (__ \ "value"    ).format[String]
      )(RenderedConfigSourceValue.apply, unlift(RenderedConfigSourceValue.unapply))

    ConfigService.ConfigSourceValue
    ( (__ \ "environment").format[Environment]
    ~ (__ \ "serviceName").format[ServiceName]
    ~ (__ \ "key"        ).format[ConfigService.KeyName]
    ~ (__ \ "value"      ).format[RenderedConfigSourceValue]
    ~ (__ \ "warning"    ).format[String]
    )(ConfigWarning.apply, unlift(ConfigWarning.unapply))
  }
}
