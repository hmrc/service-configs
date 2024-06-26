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
import uk.gov.hmrc.serviceconfigs.connector.{ConfigAsCodeConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{Dashboard, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.{GrafanaDashboardRepository, KibanaDashboardRepository}
import uk.gov.hmrc.serviceconfigs.util.ZipUtil

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DashboardService @Inject()(
  grafanaDashboardRepo         : GrafanaDashboardRepository
, kibanaDashboardRepo          : KibanaDashboardRepository
, configAsCodeConnector        : ConfigAsCodeConnector
, teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
)(using
  ec: ExecutionContext
) extends Logging:

  def updateGrafanaDashboards(): Future[Unit] =
    for
      _       <- Future.successful(logger.info(s"Updating Grafana Dashboards..."))
      repos   <- ( teamsAndRepositoriesConnector.getRepos(repoType = Some("Service"))
                 , teamsAndRepositoriesConnector.getDeletedRepos(repoType = Some("Service"))
                 ).mapN(_ ++ _)
      zip     <- configAsCodeConnector.streamGrafana()
      regex    = """src/main/scala/uk/gov/hmrc/grafanadashboards/dashboards/(.*).scala""".r
      blob     = "https://github.com/hmrc/grafana-dashboards/blob"
      items    = ZipUtil
                   .findRepos(zip, repos.map(_.repoName), regex, blob)
                   .map:
                     case (name, location) => Dashboard(serviceName = ServiceName(name.asString), location = location)
      _        = zip.close()
      _        = logger.info(s"Inserting ${items.size} Grafana Dashboards into mongo")
      count   <- grafanaDashboardRepo.putAll(items)
      _        = logger.info(s"Inserted $count Grafana Dashboards into mongo")
    yield ()

  def updateKibanaDashboards(): Future[Unit] =
    for
      _       <- Future.successful(logger.info(s"Updating Kibana Dashboards..."))
      repos   <- ( teamsAndRepositoriesConnector.getRepos(repoType = Some("Service"))
                 , teamsAndRepositoriesConnector.getDeletedRepos(repoType = Some("Service"))
                 ).mapN(_ ++ _)
      zip     <- configAsCodeConnector.streamKibana()
      regex    = """src/main/scala/uk/gov/hmrc/kibanadashboards/digitalservices/(.*).scala""".r
      blob     = "https://github.com/hmrc/kibana-dashboards/blob"
      items    = ZipUtil
                   .findRepos(zip, repos.map(_.repoName), regex, blob)
                   .map:
                     case (repo, location) => Dashboard(serviceName = ServiceName(repo.asString), location = location)
      _        = zip.close()
      _        = logger.info(s"Inserting ${items.size} Kibana Dashboards into mongo")
      count   <- kibanaDashboardRepo.putAll(items)
      _        = logger.info(s"Inserted $count Kibana Dashboards into mongo")
    yield ()
