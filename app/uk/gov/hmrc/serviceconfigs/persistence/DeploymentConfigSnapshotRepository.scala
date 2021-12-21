/*
 * Copyright 2021 HM Revenue & Customs
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
import org.mongodb.scala.bson.{BsonDocument, BsonInt32}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.DeploymentConfigSnapshot
import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.FindOneAndReplaceOptions

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigSnapshotRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
  extends PlayMongoRepository(
    mongoComponent = mongoComponent,
    collectionName = "deploymentConfigSnapshots",
    domainFormat = DeploymentConfigSnapshot.mongoFormat,
    indexes = Seq(),
  ) {

  def snapshotsForService(name: String): Future[Seq[DeploymentConfigSnapshot]] = {
    val sortParams =
      BsonDocument(
        ("date", BsonInt32(1))
      )

    collection
      .find(equal("deploymentConfig.name", name))
      .sort(sortParams)
      .toFuture()
  }

  def add(snapshot: DeploymentConfigSnapshot): Future[Unit] =
    collection
      .findOneAndReplace(
        filter = and(
          equal("deploymentConfig.name", snapshot.deploymentConfig.name),
          equal("deploymentConfig.environment", snapshot.deploymentConfig.environment.asString),
          equal("date", snapshot.date)),
        replacement = snapshot,
        options = FindOneAndReplaceOptions().upsert(true))
      .toFutureOption()
      .map(_ => ())

  def deleteAll(): Future[Unit] =
    collection
      .deleteMany(new BasicDBObject())
      .toFuture
      .map(_ => ())
}
