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

import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.Projections.include
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.serviceconfigs.model.Version

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlugVersionRepository @Inject()(
  mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = SlugInfoRepository.collectionName,
  domainFormat   = Version.mongoVersionRepositoryFormat,
  indexes        = SlugInfoRepository.indexes,
  replaceIndexes = false
) {

  // we delete explicitly when we get a delete notification
  override lazy val requiresTtlIndex = false

  def getMaxVersion(name: String) : Future[Option[Version]] =
    collection
      .find(equal("name", name))
      .projection(include("version"))
      .foldLeft(Option.empty[Version]){
        case (optMax, version) if optMax.exists(_ > version) => optMax
        case (_     , version)                               => Some(version)
      }.toFuture()
}
