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
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.persistence.model.BobbyWarningsNotificationsRunDate

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

class BobbyWarningsNotificationsRepository(
  override val mongoComponent: MongoComponent
)(
implicit
ec: ExecutionContext
) extends PlayMongoRepository[BobbyWarningsNotificationsRunDate](
  mongoComponent = mongoComponent,
  collectionName = "internalAuthConfig",
  domainFormat = BobbyWarningsNotificationsRunDate.format,
  indexes = Seq.empty,
  extraCodecs = Seq.empty
) with Transactions {

  // we replace all the data for each call to updateLastWarningDate()
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def updateLastWarningDate(): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        r <- collection.insertOne(BobbyWarningsNotificationsRunDate(LocalDate.now())).toFuture()
      } yield ()
    }

  def getLastWarningsDate(): Future[Option[LocalDate]] =
    collection
      .find()
      .map(_.lastRunDate)
      .headOption()


}


