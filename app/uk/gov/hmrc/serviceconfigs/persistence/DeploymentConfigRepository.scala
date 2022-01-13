/*
 * Copyright 2022 HM Revenue & Customs
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

import com.mongodb.BasicDBObject
import javax.inject.Inject
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.{and, equal, in}
import org.mongodb.scala.model.Projections.include
import org.mongodb.scala.model._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment}

import scala.concurrent.{ExecutionContext, Future}

class DeploymentConfigRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
  extends PlayMongoRepository(
    mongoComponent = mongoComponent,
    collectionName = "deploymentConfig",
    domainFormat   = DeploymentConfig.mongoFormat,
    indexes        = Seq(IndexModel(Indexes.ascending("name", "environment")))) {


  def add(deploymentConfig: DeploymentConfig): Future[Unit] =
    collection
      .findOneAndReplace(
        filter = and(
          equal("name", deploymentConfig.name),
          equal("environment", deploymentConfig.environment.asString)),
        replacement = deploymentConfig,
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())

  def updateAll(configs: Seq[BsonDocument]): Future[Unit] =
    if (configs.isEmpty)
      Future.successful(()) // bulkwrite with empty payload will fail
    else {
      val col     = mongoComponent.database.getCollection[BsonDocument](collectionName)
      val updates = configs.map(cfg => ReplaceOneModel(
        and(
          equal("name", cfg.get("name")),
          equal("environment", cfg.get("environment"))
        ),
        cfg,
        ReplaceOptions().upsert(true))
      )
      col.bulkWrite(updates).toFuture().map(_ => ())
    }

  def findAll(environment: Environment): Future[Seq[DeploymentConfig]] =
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

  def findAllNames(environment: Environment): Future[Seq[String]] =
    mongoComponent.database
      .getCollection[BsonDocument](collectionName)
      .find(equal("environment", environment.asString))
      .projection(include("name"))
      .map(_.getString("name").getValue)
      .toFuture()

  def delete(names: Seq[String], environment: Environment): Future[Unit] =
    collection
      .deleteMany(
        and(
          equal("environment", environment.asString),
          in("name", names)
        ))
      .toFuture()
      .map(_ => ())

  def deleteAll(): Future[Boolean] =
    collection.deleteMany(new BasicDBObject()).toFuture.map(_.wasAcknowledged())
}
