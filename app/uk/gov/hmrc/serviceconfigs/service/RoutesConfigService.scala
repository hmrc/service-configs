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

package uk.gov.hmrc.serviceconfigs.service

import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.serviceconfigs.persistence.AdminFrontendRouteRepository

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.serviceconfigs.connector.RoutesConfigConnector

@Singleton
class RoutesConfigService @Inject()(
  adminFrontendRouteRepo: AdminFrontendRouteRepository
, routesConfigConnector:  RoutesConfigConnector
)(implicit ec: ExecutionContext
) extends Logging {

  def updateAdminFrontendRoutes(): Future[Unit] =
    for {
      _      <- Future.successful(logger.info(s"Updating Admin Frontend Routes..."))
      routes <- routesConfigConnector.allAdminFrontendRoutes()
      _       = logger.info(s"Inserting ${routes.size} admin frontend routes into mongo")
      count  <- adminFrontendRouteRepo.replaceAll(routes)
      _       = logger.info(s"Inserted $count admin frontend routes into mongo")
    } yield ()

  }
