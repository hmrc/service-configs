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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, empty, equal, in, regex}
import org.mongodb.scala.model._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigGrouped, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.model.Sort

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[DeploymentConfig](
  mongoComponent = mongoComponent,
  collectionName = "deploymentConfig",
  domainFormat   = DeploymentConfig.mongoFormat,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("name", "environment")),
                     IndexModel(Indexes.ascending("environment"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format) :+ Codecs.playFormatCodec(DeploymentConfigGrouped.format)
) with Transactions {

  // we replace all the data for each call to replaceEnv
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def replaceEnv(environment: Environment, configs: Seq[BsonDocument]): Future[Int] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("environment", environment)).toFuture()
        r <- mongoComponent.database.getCollection[BsonDocument](collectionName)
                .insertMany(session, configs).toFuture()
      } yield r.getInsertedIds.size
    }

  def find(environments: Seq[Environment] = Seq.empty, serviceName: Option[ServiceName] = None): Future[Seq[DeploymentConfig]] = {
    val filters = Seq(
      Option.when(environments.nonEmpty)(in("environment", environments:_*)),
      serviceName.map(sn => equal("name", sn))
    ).flatten
    val filter = if (filters.nonEmpty)
        and(filters:_*)
      else
        empty()

    collection
      .find(filter)
      .toFuture()
  }

  // Test only
  def add(deploymentConfig: DeploymentConfig): Future[Unit] =
    collection
      .findOneAndReplace(
        filter      = and(
                        equal("name"       , deploymentConfig.serviceName),
                        equal("environment", deploymentConfig.environment)
                      ),
        replacement = deploymentConfig,
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())

  def findGrouped(
                            fuzzySearch: Option[ServiceName] = None,
                            repos: Option[Seq[Repo]],
                            sortBy: Option[String]
                          ): Future[Seq[DeploymentConfigGrouped]] = {

    val convertFields: Bson = Aggregates.addFields(
      Field("slots", BsonDocument("$toInt" -> "$slots")),
      Field("instances", BsonDocument("$toInt" -> "$instances"))
    )

    val group: Bson = Aggregates.group(
      "$name",
      BsonField("configs", BsonDocument("$push" -> "$$ROOT")),
      BsonField("count", BsonDocument("$sum" -> BsonDocument("$multiply" -> List("$slots", "$instances")))),
    )

    val repoFilter: Option[Bson] = repos.map(repos => Filters.in("name", repos.map(_.name): _*))
    val fuzzySearchFilter: Option[Bson] = fuzzySearch.map(sn => regex("name", sn.asString))

    val filters = Seq(repoFilter, fuzzySearchFilter).flatten

    val filter: Bson = if (filters.nonEmpty)
      Aggregates.filter(and(filters: _*))
    else
      empty()

    val sort = Aggregates.sort(Sort(sortBy).toBson)

    filters match {
      case Nil => collection.aggregate[DeploymentConfigGrouped](Seq(convertFields, group, sort)).toFuture()
      case _   => collection.aggregate[DeploymentConfigGrouped](Seq(filter, convertFields, group, sort)).toFuture()
    }
  }
}
