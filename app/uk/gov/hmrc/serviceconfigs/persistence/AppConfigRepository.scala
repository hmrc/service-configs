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

import org.mongodb.scala.model.Filters.{and, equal}
import org.mongodb.scala.model.{Indexes, IndexModel, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** This applies to config where there is one file per service - i.e app-config-base and app-config-$env.
  * The config is identified by either the environment it applies to, or commitId HEAD - which is the config that will apply on next deployment.
  */
@Singleton
class AppConfigRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[AppConfigRepository.AppConfig](
  mongoComponent = mongoComponent,
  collectionName = AppConfigRepository.collectionName,
  domainFormat   = AppConfigRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("repoName", "fileName", "environment", "commitId"))
                   ),
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format)
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def put(repoName: String, fileName: String, environment: Environment, commitId: String, content: String): Future[Unit] =
    collection
      .replaceOne(
        filter      = and(
                        equal("repoName"   , repoName),
                        equal("fileName"   , fileName),
                        equal("environment", environment)
                      ),
        replacement = AppConfigRepository.AppConfig(
                        environment = Some(environment),
                        repoName    = repoName,
                        fileName    = fileName,
                        commitId    = commitId,
                        content     = content
                      ),
        options     = ReplaceOptions().upsert(true)
      )
      .toFuture()
      .map(_ => ())

  def putAllHEAD(repoName: String)(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, and(equal("repoName", repoName), equal("commitId", "HEAD"))).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) =>
               AppConfigRepository.AppConfig(
                 environment = None,
                 repoName    = repoName,
                 fileName    = fileName,
                 commitId    = "HEAD",
                 content     = content
               )
             }).toFuture()
      } yield ()
    }

  def find(repoName: String, environment: Option[Environment], fileName: String): Future[Option[String]] =
    collection
      .find(
        and(
          equal("repoName", repoName),
          equal("fileName", fileName),
          environment match {
            case Some(env) => equal("environment", env)
            case None      => equal("commitId", "HEAD")
          }
        )
      )
      .headOption()
      .map(_.map(_.content))

  def delete(repoName: String, environment: Environment, fileName: String): Future[Unit] =
    collection.deleteOne(
      and(
        equal("repoName", repoName),
        equal("fileName", fileName),
        equal("environment", environment)
      )
    ).toFuture()
     .map(_ => ())
}

object AppConfigRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  val collectionName = "appConfig"

  // For each fileName, we either store environment + commitId *or* commitId HEAD
  case class AppConfig(
    environment : Option[Environment],
    repoName    : String,
    fileName    : String,
    commitId    : String,
    content     : String
  )

  val mongoFormats: Format[AppConfig] = {
    implicit val ef = Environment.format
     ( (__ \ "environment").formatNullable[Environment]
     ~ (__ \ "repoName"   ).format[String]
     ~ (__ \ "fileName"   ).format[String]
     ~ (__ \ "commitId"   ).format[String]
     ~ (__ \ "content"    ).format[String]
     )(AppConfig. apply, unlift(AppConfig.unapply))
  }
}
