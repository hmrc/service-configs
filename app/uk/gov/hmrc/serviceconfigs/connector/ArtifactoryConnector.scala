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

import play.api.{Configuration, Logging}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.StreamConverters

import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.http.client.{HttpClientV2, readEitherSource}

import javax.inject.{Inject, Singleton}
import java.util.zip.ZipInputStream
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ArtifactoryConnector @Inject()(
  config      : Configuration,
  httpClientV2: HttpClientV2
)(using
  ec : ExecutionContext,
  mat: Materializer
) extends Logging:
  import HttpReads.Implicits._

  private val artifactoryUrl: String =
    config.get[String]("artifactory.url")

  private given HeaderCarrier = HeaderCarrier()

  def getSensuZip(): Future[ZipInputStream] =
    stream(url"$artifactoryUrl/artifactory/webstore/sensu-config/output.zip")

  def getLatestHash(): Future[Option[String]] =
    httpClientV2
      .head(url"$artifactoryUrl/artifactory/webstore/sensu-config/output.zip")
      .transform(_.withRequestTimeout(20.seconds))
      .execute[HttpResponse]
      .map(_.header("x-checksum-sha256"))

  private def stream(url: java.net.URL): Future[ZipInputStream] =
    httpClientV2
      .get(url)
      .transform(_.withRequestTimeout(60.seconds))
      .stream[Either[UpstreamErrorResponse, Source[ByteString, _]]]
      .map:
        case Right(source) =>
          logger.info(s"Successfully streaming $url")
          ZipInputStream(source.runWith(StreamConverters.asInputStream()))
        case Left(error)   =>
          logger.error(s"Could not call $url - ${error.getMessage}", error)
          throw error
