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

package uk.gov.hmrc.serviceconfigs.connector

import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.StreamConverters
import play.api.Logging
import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, StringContextOps}
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.{CommitId, RepoName}

import javax.inject.{Inject, Singleton}
import java.util.zip.ZipInputStream
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigAsCodeConnector @Inject()(
  githubConfig: GithubConfig,
  httpClientV2: HttpClientV2
)(implicit
  ec : ExecutionContext,
  mat: Materializer
) extends Logging {
  import HttpReads.Implicits._

  implicit private val hc: HeaderCarrier = HeaderCarrier()

  def streamInternalAuth(): Future[ZipInputStream] =
    streamGithub(RepoName("internal-auth-config"))

  def streamBuildJobs(): Future[ZipInputStream] =
    streamGithub(RepoName("build-jobs"))

  def streamGrafana(): Future[ZipInputStream] =
    streamGithub(RepoName("grafana-dashboards"))

  def streamKibana(): Future[ZipInputStream] =
    streamGithub(RepoName("kibana-dashboards"))

  def streamAlertConfig(): Future[ZipInputStream] =
    streamGithub(RepoName("alert-config"))

  def streamFrontendRoutes(): Future[ZipInputStream] =
    streamGithub(RepoName("mdtp-frontend-routes"))

  def streamUpscanAppConfig(): Future[ZipInputStream] =
    streamGithub(RepoName("upscan-app-config"))

  def getLatestCommitId(repo: RepoName): Future[CommitId] = {
    implicit val cir = Reads.at[String]((__ \ "sha")).map(CommitId.apply)
    httpClientV2
      .get(url"${githubConfig.githubApiUrl}/repos/hmrc/${repo.asString}/commits/HEAD")
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .execute[CommitId]
  }

  def streamGithub(repo: RepoName): Future[ZipInputStream] = {
    val url = url"${githubConfig.githubApiUrl}/repos/hmrc/${repo.asString}/zipball/HEAD"
    httpClientV2
      .get(url)
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .transform(_.withRequestTimeout(120.seconds))
      .stream[Either[UpstreamErrorResponse, Source[ByteString, _]]]
      .map {
        case Right(source) =>
          logger.info(s"Successfully streaming $url")
          new ZipInputStream(source.runWith(StreamConverters.asInputStream()))
        case Left(error)   =>
          logger.error(s"Could not call $url - ${error.getMessage}", error)
          throw error
      }
  }
}
