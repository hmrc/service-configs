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
import org.mongodb.scala.model.Filters.{and, empty, regex}
import org.mongodb.scala.model._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.Transactions
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfigV2, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.model.Sort

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigRepositoryV2 @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[DeploymentConfigV2](
  mongoComponent = mongoComponent,
  collectionName = "deploymentConfig",
  domainFormat   = DeploymentConfigV2.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("name", "environment")),
                     IndexModel(Indexes.ascending("environment"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
) with Transactions {

  def getDeploymentConfigs(fuzzySearch: Option[ServiceName] = None, repos: Option[Seq[Repo]], sortBy: Option[String]): Future[Seq[DeploymentConfigV2]] = {

    val convertFields: Bson = Aggregates.addFields(
      Field("slots", BsonDocument("$toInt" -> "$slots")),
      Field("instances", BsonDocument("$toInt" -> "$instances"))
    )

    val group: Bson = Aggregates.group(
      "$name",
      BsonField("configs", BsonDocument("$push" -> "$$ROOT")),
      BsonField("count", BsonDocument("$sum" -> BsonDocument("$multiply" -> List("$slots", "$instances")))),
    )

    val repoFilter: Option[Bson] = repos.map(repos => Filters.in("name", repos.map(_.name):_*))
    val fuzzySearchFilter: Option[Bson] = fuzzySearch.map(sn => regex("name", sn.asString))

    val filters = Seq(repoFilter, fuzzySearchFilter).flatten

    val filter: Bson = if (filters.nonEmpty)
     Aggregates.filter(and(filters: _*))
    else
      empty()

    val sort = Aggregates.sort(Sort(sortBy).toBson)

    filters match {
      case Nil  => collection.aggregate(Seq(convertFields, group, sort)).toFuture()
      case _    => collection.aggregate(Seq(filter, convertFields, group, sort)).toFuture()
    }
  }
}

