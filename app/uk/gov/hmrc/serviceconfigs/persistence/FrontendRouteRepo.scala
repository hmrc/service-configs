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

import alleycats.std.iterable._
import cats.instances.all._
import cats.syntax.all._
import javax.inject.{Inject, Singleton}
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.api.libs.json.{JsObject, Json}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.Cursor
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import reactivemongo.play.json.ImplicitBSONHandlers._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FrontendRouteRepo @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[MongoFrontendRoute, BSONObjectID](
    collectionName = "frontendRoutes",
    mongo = mongo.mongoConnector.db,
    domainFormat = MongoFrontendRoute.formats
  ) {

  import MongoFrontendRoute._

  import ExecutionContext.Implicits.global

  override def indexes: Seq[Index] =
    Seq(
      Index(
        Seq("frontendPath" -> IndexType.Hashed),
        name = Some("frontendPathIdx"),
        background = true
      ),
      Index(
        Seq("service" -> IndexType.Hashed),
        name = Some("serviceIdx"),
        background = true
      )
    )

  def update(frontendRoute: MongoFrontendRoute): Future[MongoFrontendRoute] = {
    logger.debug(
      s"updating ${frontendRoute.service} ${frontendRoute.frontendPath} -> ${frontendRoute.backendPath} for env ${frontendRoute.environment}"
    )
    val s = Json.obj(
      "service" -> Json.toJson[String](frontendRoute.service),
      "environment" -> Json.toJson[String](frontendRoute.environment),
      "frontendPath" -> Json.toJson[String](frontendRoute.frontendPath)
    )

    collection
      .update(ordered = false)
      .one(q = s, u = frontendRoute, upsert = true)
      .map(_ => frontendRoute)
      .recover {
        case lastError =>
          throw new RuntimeException(
            s"failed to persist frontendRoute $frontendRoute",
            lastError
          )
      }
  }

  /** Search for frontend routes which match the path as either a prefix, or a regular expression.
    *
    * @param path a path prefix. a/b/c would match "a/b/c" exactly or "a/b/c/...", but not "a/b/c..."
    *             if no match is found, it will check regex paths starting with "a/b/.." and repeat with "a/.." if no match found, recursively.
    */
  // to test: curl "http://localhost:8460/frontend-route/search?frontendPath=account/account-details/saa" | python -mjson.tool | grep frontendPath | sort
  def searchByFrontendPath(path: String): Future[Seq[MongoFrontendRoute]] = {

    def search(query: JsObject): Future[Seq[MongoFrontendRoute]] =
      collection
        .find[JsObject, MongoFrontendRoute](query, None)
        .cursor[MongoFrontendRoute]()
        .collect[Seq](100, Cursor.FailOnError[Seq[MongoFrontendRoute]]())
        .map { res =>
          logger.info(s"query $query returned ${res.size} results")
          res
        }

    FrontendRouteRepo
      .queries(path)
      .toIterable
      .foldLeftM[Future, Seq[MongoFrontendRoute]](Seq.empty) {
        (prevRes, query) =>
          if (prevRes.isEmpty) search(query)
          else Future(prevRes)
      }
  }

  def findByService(service: String): Future[Seq[MongoFrontendRoute]] =
    collection
      .find[JsObject, MongoFrontendRoute](
        Json.obj("service" -> Json.toJson[String](service)),
        None
      )
      .cursor[MongoFrontendRoute]()
      .collect[Seq](-1, Cursor.FailOnError[Seq[MongoFrontendRoute]]())

  def findByEnvironment(environment: String): Future[Seq[MongoFrontendRoute]] =
    collection.find[JsObject, MongoFrontendRoute](
      Json.obj("environment" -> Json.toJson[String](environment)),
      None
    )
      .cursor[MongoFrontendRoute]()
      .collect[Seq](100, Cursor.FailOnError[Seq[MongoFrontendRoute]]())

  def findAllRoutes(): Future[Seq[MongoFrontendRoute]] =
    findAll()

  def clearAll(): Future[Boolean] =
    removeAll().map(_.ok)
}

object FrontendRouteRepo {
  def pathsToRegex(paths: Seq[String]): String =
    paths
      .map(_.replace("-", "(-|\\\\-)")) // '-' is escaped in regex expression
      .mkString(
        "^(\\^)?(\\/)?", // match from beginning, tolerating [^/]. match
        "\\/", // path segment separator
        "(\\/|$)"
      ) // match until end, or '/''

  def toQuery(paths: Seq[String]): JsObject =
    Json.obj(
      "frontendPath" -> Json
        .obj("$regex" -> pathsToRegex(paths), "$options" -> "i")
    ) // case insensitive

  def queries(path: String): Seq[JsObject] =
    path
      .stripPrefix("/")
      .split("/")
      .toSeq
      .inits
      .toSeq
      .dropRight(1)
      .map(toQuery)
}
