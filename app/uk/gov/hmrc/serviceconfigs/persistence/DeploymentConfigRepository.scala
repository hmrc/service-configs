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

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit ec: ExecutionContext)
  extends PlayMongoRepository(
    mongoComponent = mongoComponent,
    collectionName = "deploymentConfig",
    domainFormat   = DeploymentConfig.mongoFormat,
    indexes        = Seq(IndexModel(Indexes.ascending("name", "environment")))
) with Transactions {

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def replaceEnv(environment: Environment, configs: Seq[BsonDocument]): Future[Int] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("environment", environment.asString)).toFuture()
        r <- mongoComponent.database.getCollection[BsonDocument](collectionName)
                .insertMany(session, configs).toFuture()
      } yield r.getInsertedIds().size
    }

  def findAll: Future[Seq[DeploymentConfig]] =
    collection
      .find()
      .toFuture()

  def findAllForEnv(environment: Environment): Future[Seq[DeploymentConfig]] =
    collection.find(equal("environment", environment.asString)).toFuture()


  def findByName(environment: Environment, name: String): Future[Option[DeploymentConfig]] =
    collection
      .find(
        and(
          equal("name", name),
          equal("environment", environment.asString)
        )
      )
      .headOption()

  // Test only
  def add(deploymentConfig: DeploymentConfig): Future[Unit] =
    collection
      .findOneAndReplace(
        filter      = and(
                        equal("name", deploymentConfig.name),
                        equal("environment", deploymentConfig.environment.asString)
                      ),
        replacement = deploymentConfig,
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())

  // Test only
  def deleteAll(): Future[Unit] =
    collection
      .deleteMany(BsonDocument())
      .toFuture()
      .map(_ => ())
}
