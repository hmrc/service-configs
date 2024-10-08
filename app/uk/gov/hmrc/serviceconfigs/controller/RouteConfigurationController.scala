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

import play.api.libs.functional.syntax.toFunctionalBuilderOps

import javax.inject.{Inject, Singleton}
import play.api.libs.json.{Json, Writes, __}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.controller.RouteConfigurationController.Route
import uk.gov.hmrc.serviceconfigs.model.{Environment, RouteType, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.{AdminFrontendRouteRepository, FrontendRouteRepository}
import cats.syntax.all.*
import cats.instances.future.*

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
    serviceName: ServiceName
  , environment: Option[Environment]
  , isDevhub   : Option[Boolean] = None
  ): Future[Seq[Route]] =
    for
      mongoFrontendRoutes <- frontendRouteRepository.findRoutes(serviceName, environment, isDevhub)
      frontendRoutes      =  mongoFrontendRoutes.map: mfr =>
                               Route(
                                 path                 = mfr.frontendPath,
                                 ruleConfigurationUrl = Some(mfr.ruleConfigurationUrl),
                                 isRegex              = mfr.isRegex,
                                 routeType            = if mfr.isDevhub then RouteType.Devhub else RouteType.Frontend,
                                 environment          = mfr.environment
                               )
    yield frontendRoutes

  private def adminRoutes(
    serviceName: ServiceName
  , environment: Option[Environment]
  ): Future[Seq[Route]] =
    for
      adminFrontendRoutes <- adminFrontendRouteRepository.findByService(serviceName)
      adminRoutes         =  adminFrontendRoutes.flatMap: raw =>
                               raw.allow.keys.map: env =>
                                 Route(
                                   path                 = raw.route,
                                   ruleConfigurationUrl = Some(raw.location),
                                   routeType            = RouteType.AdminFrontend,
                                   environment          = Environment.parse(env).getOrElse(sys.error(s"Invalid Environment: $env"))
                                 )
    yield environment.fold(adminRoutes)(env => adminRoutes.filter(_.environment == env))


  def routes(
    serviceName: ServiceName
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

object RouteConfigurationController:
  case class Route(
    path                : String
  , ruleConfigurationUrl: Option[String]
  , isRegex             : Boolean = false
  , routeType           : RouteType
  , environment         : Environment
  )

  object Route:
    val writes: Writes[Route] =
      ( (__ \ "path"                ).write[String]
      ~ (__ \ "ruleConfigurationUrl").writeNullable[String]
      ~ (__ \ "isRegex"             ).write[Boolean]
      ~ (__ \ "routeType"           ).write[RouteType](RouteType.writes)
      ~ (__ \ "environment"         ).write[Environment](Environment.format)
      )(r => Tuple.fromProductTyped(r))

end RouteConfigurationController
