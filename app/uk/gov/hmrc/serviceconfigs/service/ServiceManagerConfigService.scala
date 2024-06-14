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

import cats.implicits._

import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.connector.{ConfigConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.ServiceName
import uk.gov.hmrc.serviceconfigs.persistence.ServiceManagerConfigRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ServiceManagerConfigService @Inject()(
  serviceManagerConfigRepository: ServiceManagerConfigRepository
, configConnector               : ConfigConnector
, teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
)(using ec: ExecutionContext
) extends Logging {

  private given HeaderCarrier = HeaderCarrier()

  def update(): Future[Unit] =
    for {
      _        <- Future.successful(logger.info(s"Updating Service Manager Config ..."))
      smConfig <- configConnector.serviceManagerConfig().map(_.getOrElse("").linesIterator.zipWithIndex.toList)
      repos   <- ( teamsAndRepositoriesConnector.getRepos(repoType = Some("Service"))
                 , teamsAndRepositoriesConnector.getDeletedRepos(repoType = Some("Service"))
                 ).mapN(_ ++ _)
      items     = repos.flatMap(repo =>
                    smConfig
                      .find { case (line, _) => line.contains(s"\"${repo.repoName.asString.toUpperCase.replaceAll("-", "_")}\"") }
                      .map  { case (_, idx ) => ServiceManagerConfigRepository.ServiceManagerConfig(ServiceName(repo.repoName.asString), s"https://github.com/hmrc/service-manager-config/blob/main/services.json#L${idx + 1}") }
                  )
      _         = logger.info(s"Inserting ${items.size} Service Manager Config into mongo")
      count    <- serviceManagerConfigRepository.putAll(items)
      _         = logger.info(s"Inserted $count Service Manager Config into mongo")
    } yield ()
  }
