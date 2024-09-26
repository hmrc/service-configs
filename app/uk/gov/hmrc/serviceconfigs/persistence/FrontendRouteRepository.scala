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

import cats.instances.all.*
import cats.syntax.all.*
import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters.*
import org.mongodb.scala.model.{Filters, FindOneAndReplaceOptions, IndexModel, IndexOptions, Indexes}
import play.api.Logger
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class FrontendRouteRepository @Inject()(
  mongoComponent: MongoComponent
)(using ec: ExecutionContext
) extends PlayMongoRepository[MongoFrontendRoute](
  mongoComponent = mongoComponent,
  collectionName = "frontendRoutes",
  domainFormat   = MongoFrontendRoute.format,
  indexes        = Seq(
                     IndexModel(Indexes.ascending("frontendPath")),
                     IndexModel(Indexes.ascending("service")),
                     IndexModel(Indexes.ascending("environment", "frontendPath", "service", "isDevhub"), IndexOptions().unique(true))
                   ),
  replaceIndexes = true,
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
):
  // we replace all the data for each call to replaceEnv
  override lazy val requiresTtlIndex = false

  private val logger = Logger(this.getClass)

  def update(frontendRoute: MongoFrontendRoute): Future[Unit] =
    logger.debug(
      s"updating ${frontendRoute.service} ${frontendRoute.frontendPath} -> ${frontendRoute.backendPath} for env ${frontendRoute.environment}"
    )

    collection
      .findOneAndReplace(
        filter      = and(
                        equal("service"     , frontendRoute.service),
                        equal("environment" , frontendRoute.environment),
                        equal("frontendPath", frontendRoute.frontendPath)
                      ),
        replacement = frontendRoute,
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())
      .recover:
        case lastError => throw RuntimeException(s"failed to persist frontendRoute $frontendRoute", lastError)

  /** Search for frontend routes which match the path as either a prefix, or a regular expression.
    *
    * @param path a path prefix. a/b/c would match "a/b/c" exactly or "a/b/c/...", but not "a/b/c..."
    *             if no match is found, it will check regex paths starting with "a/b/.." and repeat with "a/.." if no match found, recursively.
    */
  // to test: curl "http://localhost:8460/frontend-route/search?frontendPath=account/account-details/saa" | python -mjson.tool | grep frontendPath | sort
  def searchByFrontendPath(path: String): Future[Seq[MongoFrontendRoute]] =
    def search(query: Bson): Future[Seq[MongoFrontendRoute]] =
      collection
        .find(query)
        .limit(100)
        .toFuture()
        .map: res =>
          logger.info(s"query $query returned ${res.size} results")
          res

    FrontendRouteRepository
      .queries(path)
      .toList
      .foldLeftM[Future, Seq[MongoFrontendRoute]](Seq.empty): (prevRes, query) =>
        if   prevRes.isEmpty then search(query)
        else                      Future.successful(prevRes)

  def findByService(serviceName: ServiceName): Future[Seq[MongoFrontendRoute]] =
    collection
      .find(equal("service", serviceName))
      .toFuture()

  def findByEnvironment(environment: Environment): Future[Seq[MongoFrontendRoute]] =
    collection
      .find(equal("environment", environment))
      .toFuture()
  
  def findRoutes(serviceName: ServiceName, environment: Option[Environment], isDevhub: Option[Boolean]): Future[Seq[MongoFrontendRoute]] =
    collection
      .find(
        Filters.and(
          Filters.equal("service", serviceName)
        , isDevhub.fold(Filters.empty)(dh => Filters.equal("isDevhub", dh))
        , environment.fold(Filters.empty)(env => Filters.equal("environment", env.asString))
        )
      ).toFuture()

  def replaceEnv(environment: Environment, routes: Set[MongoFrontendRoute]): Future[Unit] =
    MongoUtils.replace[MongoFrontendRoute](
      collection    = collection,
      newVals       = routes.toSeq,
      oldValsFilter = equal("environment", environment),
      compareById   = (a, b) =>
                        a.environment  == b.environment &&
                        a.isDevhub     == b.isDevhub    &&
                        a.service      == b.service     &&
                        a.frontendPath == b.frontendPath,
      filterById    = entry =>
                        and(
                          equal("environment" , environment),
                          equal("isDevhub"    , entry.isDevhub),
                          equal("service"     , entry.service),
                          equal("frontendPath", entry.frontendPath)
                        )
      )

  def findAllFrontendServices(): Future[Seq[ServiceName]] =
    collection
      .distinct[String]("service")
      .toFuture()
      .map: 
        _.collect:
          case s if !s.contains("$") => ServiceName(s) // excludes service name anomalies that contain $

object FrontendRouteRepository:
  def pathsToRegex(paths: Seq[String]): String =
    paths
      .map(_.replace("-", "(-|\\\\-)")) // '-' is escaped in regex expression
      .mkString(
        "^(\\^)?(\\/)?", // match from beginning, tolerating [^/]. match
        "\\/", // path segment separator
        "(\\/|[^-A-Za-z0-9]|$)" // match until end, or '/'' or a non alpha expression e.g. a regex
      )

  def toQuery(paths: Seq[String]): Bson =
    Document(
      "frontendPath" ->
        Document(
          "$regex"   -> pathsToRegex(paths),
          "$options" -> "i" // case insensitive
        )
    )

  def queries(path: String): Seq[Bson] =
    path
      .stripPrefix("/")
      .split("/")
      .toSeq
      .inits
      .toSeq
      .dropRight(1)
      .map(toQuery)
