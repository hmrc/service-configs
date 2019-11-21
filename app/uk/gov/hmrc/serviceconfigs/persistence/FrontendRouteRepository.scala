/*
 * Copyright 2019 HM Revenue & Customs
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

import cats.instances.all._
import cats.syntax.all._
import com.mongodb.client.model.Indexes
import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters._
import org.mongodb.scala.model.{FindOneAndReplaceOptions, IndexModel, IndexOptions}
import play.api.Logger
import uk.gov.hmrc.mongo.component.MongoComponent
import uk.gov.hmrc.mongo.play.PlayMongoCollection
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FrontendRouteRepository @Inject()(mongoComponent: MongoComponent)(implicit ec: ExecutionContext)
    extends PlayMongoCollection(
      mongoComponent = mongoComponent,
      collectionName = "frontendRoutes",
      domainFormat   = MongoFrontendRoute.formats,
      indexes = Seq(
        IndexModel(Indexes.hashed("frontendPath"), IndexOptions().background(true).name("frontendPathIdx")),
        IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx"))
      )
    ) {

  private val logger = Logger(getClass)

  def update(frontendRoute: MongoFrontendRoute): Future[Unit] = {
    logger.debug(
      s"updating ${frontendRoute.service} ${frontendRoute.frontendPath} -> ${frontendRoute.backendPath} for env ${frontendRoute.environment}"
    )

    collection
      .findOneAndReplace(
        filter      = and(
                        equal("service"     , frontendRoute.service),
                        equal("environment" , frontendRoute.environment),
                        equal("frontendPath", frontendRoute.frontendPath),
                        equal("routesFile"  , frontendRoute.routesFile)
                      ),
        replacement = frontendRoute,
        options     = FindOneAndReplaceOptions().upsert(true))
      .toFuture()
      .map(_ => ())
      .recover {
        case lastError => throw new RuntimeException(s"failed to persist frontendRoute $frontendRoute", lastError)
      }
  }

  /** Search for frontend routes which match the path as either a prefix, or a regular expression.
    *
    * @param path a path prefix. a/b/c would match "a/b/c" exactly or "a/b/c/...", but not "a/b/c..."
    *             if no match is found, it will check regex paths starting with "a/b/.." and repeat with "a/.." if no match found, recursively.
    */
  // to test: curl "http://localhost:8460/frontend-route/search?frontendPath=account/account-details/saa" | python -mjson.tool | grep frontendPath | sort
  def searchByFrontendPath(path: String): Future[Seq[MongoFrontendRoute]] = {

    def search(query: Bson): Future[Seq[MongoFrontendRoute]] =
      collection
        .find(query)
        .limit(100)
        .toFuture()
        .map { res =>
          logger.info(s"query $query returned ${res.size} results")
          res
        }

    FrontendRouteRepository
      .queries(path)
      .toList
      .foldLeftM[Future, Seq[MongoFrontendRoute]](Seq.empty) { (prevRes, query) =>
        if (prevRes.isEmpty) search(query)
        else Future(prevRes)
      }
  }

  def findByService(service: String): Future[Seq[MongoFrontendRoute]] =
    collection
      .find(equal("service", service))
      .toFuture()

  def findByEnvironment(environment: String): Future[Seq[MongoFrontendRoute]] =
    collection
      .find(equal("environment", environment))
      .toFuture()

  def findAllRoutes(): Future[Seq[MongoFrontendRoute]] = collection.find().toFuture()

  def clearAll(): Future[Boolean] = collection.drop().toFutureOption().map(_.isDefined)
}

object FrontendRouteRepository {
  def pathsToRegex(paths: Seq[String]): String =
    paths
      .map(_.replace("-", "(-|\\\\-)")) // '-' is escaped in regex expression
      .mkString(
        "^(\\^)?(\\/)?", // match from beginning, tolerating [^/]. match
        "\\/", // path segment separator
        "(\\/|$)" // match until end, or '/''
      )

  def toQuery(paths: Seq[String]): Bson =
    Document("frontendPath" ->
      Document(
        "$regex"   -> pathsToRegex(paths),
        "$options" -> "i"// case insensitive
        ))

  def queries(path: String): Seq[Bson] =
    path
      .stripPrefix("/")
      .split("/")
      .toSeq
      .inits
      .toSeq
      .dropRight(1)
      .map(toQuery)
}
