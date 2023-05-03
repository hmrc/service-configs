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
import org.mongodb.scala.model.Indexes._
import org.mongodb.scala.model.{IndexModel, IndexOptions, ReplaceOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.PlayMongoRepository
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AppConfigCommonRepository @Inject()(
  override val mongoComponent: MongoComponent
)(implicit
  ec: ExecutionContext
) extends PlayMongoRepository[AppConfigCommonRepository.AppConfigCommon](
  mongoComponent = mongoComponent,
  collectionName = "appConfigCommon",
  domainFormat   = AppConfigCommonRepository.mongoFormats,
  indexes        = Seq(
                     IndexModel(hashed("fileName"), IndexOptions().name("fileNameIdx"))
                   )
) with Transactions {

  // we replace all the data for each call to putAll
  override lazy val requiresTtlIndex = false

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict

  def put(serviceName: String, environment: Environment, fileName: String, commitId: String, content: String): Future[Unit] =
    // TODO how to clean up unreferenced versions?
    // TODO we only really need to replace if commitId is the same (e.g. "HEAD")
    collection.replaceOne(
      and(
        equal("serviceName", serviceName),
        equal("environment", environment)
      ),
      AppConfigCommonRepository.AppConfigCommon(
        serviceName = Some(serviceName),
        environment = environment,
        fileName    = fileName,
        commitId    = commitId,
        content     = content
      ),
      ReplaceOptions().upsert(true)
    )
      .toFuture()
      .map(_ => ())

  def putAllHEAD(config: Map[String, String]): Future[Unit] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, equal("commiId", "HEAD")).toFuture()
        _ <- collection.insertMany(session, config.toSeq.map { case (fileName, content) =>
               val environment = Environment.parse(fileName.takeWhile(_ != '-')).getOrElse {
                                   sys.error(s"Could not extract environment from $fileName")// TODO handle better
                                 }
               AppConfigCommonRepository.AppConfigCommon(
                 serviceName = None,
                 environment = environment,
                 fileName    = fileName,
                 commitId    = "HEAD",
                 content     = content
               )
             }).toFuture()
      } yield ()
    }

  def findForLatest(environment: Environment, serviceType: String): Future[Option[String]] = {
    // We do store data for `api-microservice` but it is not yaml - it's a link to the `microservice` file
    val st = serviceType match {
      case "api-microservice" => "microservice"
      case other              => other
    }
    collection
      .find(
        and(
          equal("fileName", s"${environment.asString}-$st-common.yaml"),
          equal("commitId", "HEAD")
        )
      )
      .headOption()
      .map(_.map(_.content))
  }

  def findForDeployed(environment: Environment, serviceName: String): Future[Option[String]] =
    collection
      .find(
        // we don't need filename, as there should only be one file used per serviceName/environment
        and(
          equal("serviceName", serviceName),
          equal("environment", environment)
        )
      )
      .headOption()
      .map(_.map(_.content))
}

object AppConfigCommonRepository {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Format, __}

  case class AppConfigCommon(
    serviceName: Option[String],
    environment: Environment,
    fileName   : String,
    commitId   : String,
    content    : String
  )

  val mongoFormats: Format[AppConfigCommon] = {
    implicit val ef = Environment.format
     ( (__ \ "serviceName").formatNullable[String]
     ~ (__ \ "environment").format[Environment]
     ~ (__ \ "fileName"   ).format[String]
     ~ (__ \ "commitId"   ).format[String]
     ~ (__ \ "content"    ).format[String]
     )(AppConfigCommon.apply, unlift(AppConfigCommon.unapply))
  }
}
