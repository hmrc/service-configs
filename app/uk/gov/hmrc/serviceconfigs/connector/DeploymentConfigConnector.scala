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

import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.stream.Materializer
import akka.stream.scaladsl.StreamConverters
import play.api.Logging

import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.UpstreamErrorResponse
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.Environment

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigConnector @Inject()(
  githubConfig: GithubConfig,
  httpClientV2: HttpClientV2
)(implicit
  ec : ExecutionContext,
  mat: Materializer
) extends Logging {

  implicit private val hc = HeaderCarrier()

  def getAppConfigZip(environment: Environment): Future[java.io.InputStream] = {
    val url = url"${githubConfig.githubApiUrl}/repos/hmrc/app-config-${environment.asString}/zipball/HEAD"
    httpClientV2
      .get(url)
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .transform(_.withRequestTimeout(60.seconds))
      .stream[Either[UpstreamErrorResponse, Source[ByteString, _]]]
      .map {
        case Right(source) =>
          logger.info(s"Successfully downloaded $url")
          source.runWith(StreamConverters.asInputStream(readTimeout = 60.seconds))
        case Left(error)   =>
          logger.error(s"Could not call $url - ${error.getMessage}", error)
          throw error
      }
  }
}
