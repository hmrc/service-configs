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

package uk.gov.hmrc.serviceconfigs.connector

import akka.stream.Materializer
import akka.stream.scaladsl.StreamConverters
import play.api.Logging
import play.api.libs.ws.WSClient
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.Environment

import java.io.InputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigConnector @Inject()(
  githubConfig: GithubConfig,
  wsClient    : WSClient
)(implicit
  ec          : ExecutionContext,
  materializer: Materializer
) extends Logging {

  def getAppConfigZip(environment: Environment): Future[InputStream] = {
    val repoName = s"app-config-${environment.asString}"
    val url      = url"${githubConfig.githubApiUrl}/repos/hmrc/$repoName/zipball/HEAD"
    wsClient
      .url(url.toString)
      .withMethod("GET")
      .withHttpHeaders("Authorization" -> s"token ${githubConfig.githubToken}")
      .withFollowRedirects(true)
      .withRequestTimeout(60.seconds)
      .stream()
      .map { resp =>
        logger.info(s"Download of ${url} responded with ${resp.status} ${resp.statusText}")
        resp.bodyAsSource.async.runWith(StreamConverters.asInputStream(readTimeout = 60.seconds))
      }
  }
}
