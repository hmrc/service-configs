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

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Source, StreamConverters}
import org.apache.pekko.util.ByteString
import play.api.Logging
import uk.gov.hmrc.http.client.{HttpClientV2, readEitherSource}
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps}
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
)(using
  ec : ExecutionContext,
  mat: Materializer
) extends Logging:
  import HttpReads.Implicits._

  private given HeaderCarrier = HeaderCarrier()

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

  def getLatestCommitId(repo: RepoName): Future[CommitId] =
    val url = url"${githubConfig.githubApiUrl}/repos/hmrc/${repo.asString}/commits/HEAD"
    httpClientV2
      .get(url)
      .setHeader(
        "Authorization" -> s"token ${githubConfig.githubToken}",
        // we're only interested in the sha, without this, we may see 422: "The request could not be processed because too many files changed"
        "Accept"        -> "application/vnd.github.sha"
      )
      .withProxy
      .execute[Either[UpstreamErrorResponse, HttpResponse]]
      .map:
        case Right(rsp)  => CommitId(rsp.body)
        case Left(error) =>
          logger.error(s"Could not call $url - ${error.getMessage}", error)
          throw error

  def streamGithub(repo: RepoName): Future[ZipInputStream] =
    val url = url"${githubConfig.githubApiUrl}/repos/hmrc/${repo.asString}/zipball/HEAD"
    httpClientV2
      .get(url)
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .transform(_.withRequestTimeout(120.seconds))
      .stream[Either[UpstreamErrorResponse, Source[ByteString, _]]]
      .map:
        case Right(source) =>
          logger.info(s"Successfully streaming $url")
          ZipInputStream(source.runWith(StreamConverters.asInputStream()))
        case Left(error)   =>
          logger.error(s"Could not call $url - ${error.getMessage}", error)
          throw error
