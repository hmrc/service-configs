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
import org.mongodb.scala.model.Filters.{and, empty, equal, in}
import org.mongodb.scala.model._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ServiceName}

import javax.inject.{Inject, Singleton}
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
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
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

  def find(environments: Seq[Environment] = Seq.empty, serviceName: Option[ServiceName] = None, repos: Option[Seq[Repo]] = None): Future[Seq[DeploymentConfig]] = {

    val filters = Seq(
      Option.when(environments.nonEmpty)(in("environment", environments:_*)),
      repos.map(repos => Filters.in("name", repos.map(_.name): _*)),
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
}
