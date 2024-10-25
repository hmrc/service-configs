/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.controller

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Format, Json, Writes, __}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{Environment, Route, RouteType, ServiceName, ShutteringRoutes}
import uk.gov.hmrc.serviceconfigs.persistence.{AdminFrontendRouteRepository, FrontendRouteRepository}
import cats.syntax.all.*
import cats.instances.future.*
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RouteConfigurationController @Inject()(
  frontendRouteRepository     : FrontendRouteRepository,
  adminFrontendRouteRepository: AdminFrontendRouteRepository,
  mcc                         : MessagesControllerComponents
)(using
  ec: ExecutionContext
) extends BackendController(mcc):

  private given Writes[Route] = Route.writes

  private def frontendRoutes(
    serviceName: Option[ServiceName]
  , environment: Option[Environment]
  , isDevhub   : Option[Boolean] = None
  ): Future[Seq[Route]] =
    for
      mongoFrontendRoutes <- frontendRouteRepository.findRoutes(serviceName, environment, isDevhub)
      frontendRoutes      =  mongoFrontendRoutes.map(Route.fromMongo)
    yield frontendRoutes

  private def adminRoutes(
    serviceName: Option[ServiceName]
  , environment: Option[Environment]
  ): Future[Seq[Route]] =
    for
      adminFrontendRoutes <- adminFrontendRouteRepository.findRoutes(serviceName)
      adminRoutes         =  adminFrontendRoutes.flatMap: raw =>
                               raw.allow.keys.map: env =>
                                 Route(
                                   serviceName          = raw.serviceName,
                                   path                 = raw.route,
                                   ruleConfigurationUrl = Some(raw.location),
                                   routeType            = RouteType.AdminFrontend,
                                   environment          = Environment.parse(env).getOrElse(sys.error(s"Invalid Environment: $env"))
                                 )
    yield environment.fold(adminRoutes)(env => adminRoutes.filter(_.environment == env))

  def routes(
    serviceName: Option[ServiceName]
  , environment: Option[Environment]
  , routeType  : Option[RouteType]
  ): Action[AnyContent] =
    Action.async:
      for
        routes <- routeType match
                    case Some(RouteType.Frontend)      => frontendRoutes(serviceName, environment, isDevhub = Some(false))
                    case Some(RouteType.Devhub)        => frontendRoutes(serviceName, environment, isDevhub = Some(true))
                    case Some(RouteType.AdminFrontend) => adminRoutes(serviceName, environment)
                    case None                          => (frontendRoutes(serviceName, environment),
                                                           adminRoutes(serviceName, environment)).mapN(_ ++ _)
      yield Ok(Json.toJson(routes.sortBy(_.path)))

  def searchByFrontendPath(
    frontendPath: String
  , environment : Option[Environment]
  ): Action[AnyContent] =
    Action.async:
      frontendRouteRepository.searchByFrontendPath(frontendPath, environment)
        .map(_.map(Route.fromMongo))
        .map(Json.toJson(_))
        .map(Ok(_))

  def shutteringRoutes(environment: Environment): Action[AnyContent] =
    given Writes[ShutteringRoutes] = ShutteringRoutes.writes
    Action.async:
      frontendRouteRepository.findRoutes(None, Some(environment), isDevhub = Some(false))
        .map(ShutteringRoutes.fromMongo)
        .map(Json.toJson(_))
        .map(Ok(_))
