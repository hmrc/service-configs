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

import cats.implicits._

import javax.inject.{Inject, Singleton}
import org.yaml.snakeyaml.Yaml
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, StringContextOps, UpstreamErrorResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import HttpReads.Implicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Try

@Singleton
class GithubRawConnector @Inject()(
  httpClientV2: HttpClientV2
, githubConfig: GithubConfig
)(implicit
  ec: ExecutionContext
) {

  def decommissionedServices()(implicit hc: HeaderCarrier): Future[List[String]] = {
    val url = url"${githubConfig.githubRawUrl}/hmrc/decommissioning/main/decommissioned-microservices.yaml"
    httpClientV2
      .get(url)
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .execute[Either[UpstreamErrorResponse, HttpResponse]]
      .flatMap(
        _.flatMap(res =>
          Try(
            new Yaml()
              .load(res.body)
              .asInstanceOf[java.util.List[java.util.LinkedHashMap[String, String]]].asScala.toList
              .flatMap(_.asScala.get("service_name").toList)
          ).toEither
        )
        .leftMap(e => new RuntimeException(s"Failed to call $url: $e", e))
        .fold(Future.failed, Future.successful)
      )
  }
}
