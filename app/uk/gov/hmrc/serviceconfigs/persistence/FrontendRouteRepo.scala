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



import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json}
import play.api.libs.json.Json.toJsFieldJsValueWrapper
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.{Index, IndexType}
import reactivemongo.bson.BSONObjectID
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import reactivemongo.play.json.ImplicitBSONHandlers._
import reactivemongo.api.{Cursor, DB}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FrontendRouteRepo @Inject()(mongo: ReactiveMongoComponent)
  extends ReactiveRepository[MongoFrontendRoute, BSONObjectID](
    collectionName = "frontendRoutes",
    mongo          = mongo.mongoConnector.db,
    domainFormat   = MongoFrontendRoute.formats) {

  import MongoFrontendRoute._

  override def ensureIndexes(implicit ec: ExecutionContext): Future[Seq[Boolean]] = localEnsureIndexes

  private def localEnsureIndexes =
    Future.sequence(
      Seq(
        collection
          .indexesManager
          .ensure(
            Index(
              Seq("frontendPath" -> IndexType.Hashed),
              name       = Some("frontendPathIdx"),
              unique     = true,
              background = true))))


  def update(frontendRoute: MongoFrontendRoute): Future[MongoFrontendRoute] = {
    logger.debug(s"updating ${frontendRoute.service} ${frontendRoute.frontendPath} -> ${frontendRoute.backendPath} for env ${frontendRoute.environment}")
    val s = Json.obj(
      "service" -> Json.toJson[String](frontendRoute.service),
      "environment" -> Json.toJson[String](frontendRoute.environment),
      "frontendPath" -> Json.toJson[String](frontendRoute.frontendPath))

    collection.update(selector = s, update = frontendRoute, upsert = true)
      .map(_ => frontendRoute)
      .recover { case lastError => throw new RuntimeException(s"failed to persist frontendRoute $frontendRoute", lastError) }
  }

  def findByPath(path: String) : Future[Option[MongoFrontendRoute]] =
    collection.find(Json.obj("frontendPath" -> Json.toJson[String](path))).one[MongoFrontendRoute]


  /** Search for frontend routes which match the path as either a prefix, or a regular expression.
    * @param path a path prefix. a/b/c would match "a/b/c" exactly or "a/b/c/...", but not "a/b/c..."
    *             if no match is found, it will check regex paths starting with "a/b/.." and repeat with "a/.." if no match found, recursively.
    */
  // to test: curl "http://localhost:8460/frontend-route/search?frontendPath=account/account-details/saa" | python -mjson.tool | grep frontendPath | sort
  def searchByFrontendPath(path: String): Future[Seq[MongoFrontendRoute]] = {

    def search(paths: Seq[String]): Future[Seq[MongoFrontendRoute]] = {
      val s = Json.obj(
        "frontendPath" -> Json.obj(
          "$regex"   -> FrontendRouteRepo.pathsToRegex(paths),
          "$options" -> "i")) // case insensitive
      collection
        .find(s)
        .cursor[MongoFrontendRoute]()
        .collect[Seq](100, Cursor.FailOnError[Seq[MongoFrontendRoute]]())
        .flatMap { res =>
          logger.info(s"searched for '$path' with $s - found ${res.size} results")
          if (res.isEmpty && !paths.dropRight(1).isEmpty)
            search(paths.dropRight(1))
          else Future(res)
        }
    }

    val paths = path.stripPrefix("/").split("/")
    search(paths)
  }

  def findByService(service: String) : Future[Seq[MongoFrontendRoute]] =
    collection.find(Json.obj("service" -> Json.toJson[String](service))).cursor[MongoFrontendRoute]().collect[Seq](100, Cursor.FailOnError[Seq[MongoFrontendRoute]]())

  def findAllRoutes() : Future[Seq[MongoFrontendRoute]] = findAll()

  def clearAll() : Future[Boolean] = removeAll().map(_.ok)
}

object FrontendRouteRepo {
  // TODO test
  def pathsToRegex(paths: Seq[String]): String =
    paths
      .map(_.replace("-", "\\\\-")) // '-' is escaped in nginx expression
      .mkString(
        "^(\\^)?(\\/)?",  // match from beginning, tolerating [^/]. match
        "\\/",          // path segment separator
        "(\\/|$)")       // match until end, or '/''
}