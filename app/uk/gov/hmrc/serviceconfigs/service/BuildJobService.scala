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
import uk.gov.hmrc.serviceconfigs.connector.{ConfigAsCodeConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.BuildJob
import uk.gov.hmrc.serviceconfigs.persistence.BuildJobRepository
import uk.gov.hmrc.serviceconfigs.util.ZipUtil

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BuildJobService @Inject()(
  buildJobRepository           : BuildJobRepository
, configAsCodeConnector        : ConfigAsCodeConnector
, teamsAndRepositoriesConnector: TeamsAndRepositoriesConnector
)(implicit ec: ExecutionContext
) extends Logging {

  def updateBuildJobs(): Future[Unit] =
    for {
      _      <- Future.successful(logger.info(s"Updating Build Jobs..."))
      repos  <- teamsAndRepositoriesConnector.getRepos(repoType = Some("Service"))
      zip    <- configAsCodeConnector.streamBuildJobs()
      regex   = """jobs/live/(.*).groovy""".r
      blob    = "https://github.com/hmrc/build-jobs/blob"
      items   = ZipUtil
                  .findRepos(zip, repos, regex, blob)
                  .map { case (repo, location) => BuildJob(service = repo.name, location = location) }
      _       = zip.close()
      _       = logger.info(s"Inserting ${items.size} Build Jobs into mongo")
      count  <- buildJobRepository.putAll(items)
      _       = logger.info(s"Inserted $count Build Jobs into mongo")
    } yield ()
  }
