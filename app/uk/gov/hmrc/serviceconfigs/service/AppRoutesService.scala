/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.service

import javax.inject.{Inject, Singleton}
import cats.implicits.*
import play.api.Logging
import play.routes.compiler.*
import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.model.{AppRoute, AppRoutes, RepoName, RouteParameter, ServiceName, ServiceToRepoName, Version}
import uk.gov.hmrc.serviceconfigs.persistence.AppRoutesRepository

import scala.concurrent.{ExecutionContext, Future}
import java.io.File
import play.api.libs.json.{Json, Reads}

@Singleton
class AppRoutesService @Inject()(
  appRoutesRepo: AppRoutesRepository,
  configAsCode : ConfigAsCodeConnector
)(using ec: ExecutionContext
) extends Logging:

  private[service] val serviceRepoMappings: List[ServiceToRepoName] =
    given Reads[ServiceToRepoName]  = ServiceToRepoName.reads
    Json.parse(getClass.getResourceAsStream("/service-to-repo-names.json")).as[List[ServiceToRepoName]]

  private val ignoredIncludes = Set("health.Routes", "hmrcfrontend.Routes")

  def update(serviceName: ServiceName, version: Version): Future[Unit] =
    val repoName = toRepoName(serviceName)
    for {
      routes <- parseAllRoutes(repoName, version)
      _      <- appRoutesRepo.put(AppRoutes(serviceName, version, routes))
    } yield ()
  
  private def toRepoName(serviceName: ServiceName): RepoName =
    serviceRepoMappings
      .find(_.serviceName == serviceName)
      .map(_.repoName)
      .getOrElse(
        RepoName(serviceName.asString)
      )
  
  private def parseAllRoutes(repoName: RepoName, version: Version): Future[Seq[AppRoute]] =
    walkRoutes(repoName, version, "conf/prod.routes", prefix = "")

  private def walkRoutes(
    repoName  : RepoName,
    version   : Version,
    routesPath: String,
    prefix    : String
  ): Future[Seq[AppRoute]] =
    configAsCode
      .getVersionedFileContent(repoName, routesPath, version)
      .flatMap:
        case None =>
          logger.warn(s"Routes file not found: $routesPath for ${repoName.asString}:v$version")
          Future.successful(Seq.empty)
        case Some(content) =>
          val rules = parseRoutesContent(content, routesPath)
          rules.traverse:
            case route: Route =>
              Future.successful(Seq(normalise(route, prefix)))
            case include: Include if ignoredIncludes.contains(include.router) =>
              Future.successful(Seq.empty)
            case include: Include =>
              val includePath = s"conf/${include.router.toLowerCase}"
              val newPrefix   = if (prefix.isEmpty) include.prefix else s"$prefix/${include.prefix}".replace("//", "/")
              walkRoutes(repoName, version, includePath, newPrefix)
          .map(_.flatten)

  private def parseRoutesContent(content: String, fileName: String): Seq[Rule] =
    // https://github.com/playframework/playframework/blob/3.0.8/dev-mode/play-routes-compiler/src/main/scala/play/routes/compiler/RoutesFileParser.scala#L33
    // only used for error reporting, doesn't need to exist on disk
    val file = new File(fileName)

    RoutesFileParser.parseContent(content, file) match
      case Right(rules) => rules
      case Left(errors) =>
        val errorMessages = errors.map(e => s"${e.source}: ${e.message} (line ${e.line.getOrElse("?")})").mkString(", ")
        logger.warn(s"Parse errors in $fileName: $errorMessages")
        throw new RuntimeException(s"Failed to parse routes file: $fileName")

  private def normalise(route: Route, prefix: String): AppRoute =
    val pathParamNames =
      route.path.parts.collect: 
        case DynamicPart(name, _, _) => name 
      .toSet

    val pathString =
      route.path.parts.map:
        case StaticPart(value) => value
        case DynamicPart(name, constraint, _) => 
          if (constraint == ".+") then s"*$name" else s":$name"
      .mkString

    val fullPath = s"/$prefix/$pathString".replace("//", "/")

    val parameters =
      route.call.parameters.fold(Seq.empty[RouteParameter]): params =>
        params.map: param =>
          RouteParameter(
            name         = param.name,
            typeName     = param.typeName,
            fixed        = param.fixed,
            default      = param.default,
            isPathParam  = pathParamNames.contains(param.name),
            isQueryParam = !pathParamNames.contains(param.name) && param.fixed.isEmpty
          )

    AppRoute(
      verb       = route.verb.value,
      path       = fullPath,
      controller = route.call.packageName.fold("")(_ + ".") + route.call.controller,
      method     = route.call.method,
      parameters = parameters,
      modifiers  = route.modifiers.map(_.value)
    )
