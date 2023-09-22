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

import org.mongodb.scala.model.{Filters, FindOneAndReplaceOptions}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.serviceconfigs.persistence.BobbyWarningsNotificationsRepository.BobbyWarningsNotificationsRunDate

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyWarningsNotificationsRepository @Inject() (
  mongoComponent: MongoComponent
)(
implicit
ec: ExecutionContext
) extends PlayMongoRepository[BobbyWarningsNotificationsRunDate](
  mongoComponent = mongoComponent,
  collectionName = "bobbyWarningsNotificationsRunDate",
  domainFormat = BobbyWarningsNotificationsRunDate.format,
  indexes = Seq.empty,
  extraCodecs = Seq.empty
) {

  // we replace all the data for each call to updateLastWarningDate()
  override lazy val requiresTtlIndex = false


  def setLastRunDate(lastRunDate: Instant): Future[Unit] =
    collection.findOneAndReplace(
      filter = Filters.empty(),
      replacement = BobbyWarningsNotificationsRunDate(lastRunDate),
      options = FindOneAndReplaceOptions().upsert(true)
   )
     .toFutureOption()
     .map(_ => ())
      .recover {
        case lastError => throw new RuntimeException(s"failed to update last Rundate ${lastError.getMessage()}", lastError)
      }

  def getLastWarningsDate(): Future[Option[Instant]] =
    collection
      .find()
      .map(_.lastRun)
      .headOption()


}

object BobbyWarningsNotificationsRepository {
  case class BobbyWarningsNotificationsRunDate(lastRun: Instant) extends AnyVal

  object BobbyWarningsNotificationsRunDate {
    val format: OFormat[BobbyWarningsNotificationsRunDate] = {
      import MongoJavatimeFormats.Implicits.jatInstantFormat
      Format.at[Instant](__ \ "lastRunDate").inmap[BobbyWarningsNotificationsRunDate](BobbyWarningsNotificationsRunDate.apply, _.lastRun)
    }
  }
}



