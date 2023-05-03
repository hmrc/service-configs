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

import org.mongodb.scala.model.Filters.{and, equal, notEqual}
import org.mongodb.scala.model.{Indexes, IndexModel, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppConfigEnvRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[AppConfigEnvRepository.AppConfigEnv](
  mongoComponent = mongoComponent,
  collectionName = AppConfigEnvRepository.collectionName,
  domainFormat   = AppConfigEnvRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(Indexes.hashed("environment")),
                     IndexModel(Indexes.ascending("environment", "commitId", "fileName"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format)
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def put(serviceName: String, environment: Environment, fileName: String, commitId: String, content: String): Future[Unit] =
    collection
      .replaceOne(
        and(
          equal("serviceName", serviceName),
          equal("environment", environment),
          notEqual("commitId", "HEAD")
        ),
        AppConfigEnvRepository.AppConfigEnv(
          serviceName = serviceName,
          environment = environment,
          fileName    = fileName,
          commitId    = commitId,
          content     = content
        ),
        ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def putAllHEAD(environment: Environment, config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, and(equal("environment", environment), equal("commitId", "HEAD"))).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) =>
               AppConfigEnvRepository.AppConfigEnv(
                 serviceName = fileName.stripSuffix(".yaml"),
                 environment = environment,
                 fileName    = fileName,
                 commitId    = "HEAD",
                 content     = content
               )
             }).toFuture()
        // if there are any non HEAD that are not being inserted here, should they be removed? i.e. if no file at HEAD, then it's been decomissioned?
      } yield ()
    }

  def find(environment: Environment, fileName: String, latest: Boolean): Future[Option[String]] =
    collection
      .find(
        and(
          equal("environment", environment),
          equal("fileName"   , fileName),
          if (latest)
            equal("commitId", "HEAD")
          else
            notEqual("commitId", "HEAD")
        )
      )
      .headOption()
      .map(_.map(_.content))
}

object AppConfigEnvRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "appConfigEnv"

  case class AppConfigEnv(
    serviceName : String,
    environment : Environment,
    fileName    : String,
    commitId    : String,
    content     : String
  )

  val mongoFormats: Format[AppConfigEnv] = {
    implicit val ef = Environment.format
     ( (__ \ "serviceName").format[String]
     ~ (__ \ "environment").format[Environment]
     ~ (__ \ "fileName"   ).format[String]
     ~ (__ \ "commitId"   ).format[String]
     ~ (__ \ "content"    ).format[String]
     )(AppConfigEnv. apply, unlift(AppConfigEnv.unapply))
  }
}
