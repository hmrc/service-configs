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

import java.net.URL

import com.google.inject.{Inject, Singleton}
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpReads, StringContextOps}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.serviceconfigs.model.{ApiSlugInfoFormats, DependencyConfig, SlugInfo, Version}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ArtefactProcessorConnector @Inject()(
  httpClient   : HttpClient,
  serviceConfig: ServicesConfig,
)(implicit ec: ExecutionContext
) {
  import HttpReads.Implicits._

  private val artefactProcessorUrl: URL =
    url"${serviceConfig.baseUrl("artefact-processor")}"

  def getDependencyConfigs(slugName: String, version: Version)(implicit hc: HeaderCarrier): Future[Option[Seq[DependencyConfig]]] = {
    implicit val maf = ApiSlugInfoFormats.dependencyConfigFormat
    httpClient.GET[Option[Seq[DependencyConfig]]](url"$artefactProcessorUrl/slugInfoConfigs/$slugName/${version.toString}")
  }

  def getSlugInfo(slugName: String, version: Version)(implicit hc: HeaderCarrier): Future[Option[SlugInfo]] = {
    implicit val maf = ApiSlugInfoFormats.slugInfoFormat
    httpClient.GET[Option[SlugInfo]](url"$artefactProcessorUrl/result/slug/$slugName/${version.toString}")
  }
}