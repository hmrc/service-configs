/*
 * Copyright 2022 HM Revenue & Customs
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
import uk.gov.hmrc.serviceconfigs.connector.{DashboardConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.persistence.{GrafanaDashboardRepository, KibanaDashboardRepository}
import uk.gov.hmrc.serviceconfigs.util.Scrapper

import scala.concurrent.{ExecutionContext, Future}

object DashboardService {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  case class Dashboard(service: String, location: String)

  val format: Format[Dashboard] =
    ( (__ \ "service" ).format[String]
    ~ (__ \ "location").format[String]
    )(Dashboard.apply, unlift(Dashboard.unapply))
}

@Singleton
class DashboardService @Inject()(
  grafanaDashboardRepo         : GrafanaDashboardRepository
, kibanaDashboardRepo          : KibanaDashboardRepository
, dashboardConnector           : DashboardConnector
, teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
)(implicit ec: ExecutionContext
) extends Logging {
  import DashboardService._

  def updateGrafanaDashboards(): Future[Unit] =
    for {
      _      <- Future.successful(logger.info(s"Updating Grafana Dashboards..."))
      repos  <- teamsAndRepositoriesConnector.getRepos(repoType = Some("Service"))
      zip    <- dashboardConnector.streamGrafana()
      regex   = """src/main/scala/uk/gov/hmrc/grafanadashboards/dashboards/(.*).scala""".r
      blob    = "https://github.com/hmrc/grafana-dashboards/blob"
      items   = Scrapper
                  .findRepos(zip, repos, regex, blob)
                  .map { case (repo, location) => Dashboard(service = repo.name, location = location) }
      _       = logger.info(s"Inserting ${items.size} Grafana Dashboards into mongo")
      count  <- grafanaDashboardRepo.replaceAll(items)
      _       = logger.info(s"Inserted $count Grafana Dashboards into mongo")
    } yield ()

  def updateKibanaDashboards(): Future[Unit] =
    for {
      _      <- Future.successful(logger.info(s"Updating Kibana Dashboards..."))
      repos  <- teamsAndRepositoriesConnector.getRepos(repoType = Some("Service"))
      zip    <- dashboardConnector.streamKibana()
      regex   = """src/main/scala/uk/gov/hmrc/kibanadashboards/digitalservices/(.*).scala""".r
      blob    = "https://github.com/hmrc/kibana-dashboards/blob"
      items   = Scrapper
                  .findRepos(zip, repos, regex, blob)
                  .map { case (repo, location) => Dashboard(service = repo.name, location = location) }
      _       = logger.info(s"Inserting ${items.size} Kibana Dashboards into mongo")
      count  <- kibanaDashboardRepo.replaceAll(items)
      _       = logger.info(s"Inserted $count Kibana Dashboards into mongo")
    } yield ()
  }
