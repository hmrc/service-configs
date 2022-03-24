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

import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse, StringContextOps}
import uk.gov.hmrc.serviceconfigs.config.GithubConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigConnector @Inject()(
  httpClient    : HttpClient,
  githubConfig  : GithubConfig
)(implicit ec: ExecutionContext
) extends Logging {

  def serviceConfigYaml(env: String, service: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${githubConfig.githubToken}"))
    val requestUrl = url"${githubConfig.githubRawUrl}/hmrc/app-config-$env/HEAD/$service.yaml"
    doCall(requestUrl, newHc)
  }

  def serviceConfigConf(env: String, service: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${githubConfig.githubToken}"))
    val requestUrl = url"${githubConfig.githubRawUrl}/hmrc/app-config-$env/HEAD/$service.conf"
    doCall(requestUrl, newHc)
  }

  def serviceCommonConfigYaml(env: String, serviceType: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${githubConfig.githubToken}"))
    val requestUrl = url"${githubConfig.githubRawUrl}/hmrc/app-config-common/HEAD/$env-$serviceType-common.yaml"
    doCall(requestUrl, newHc)
  }

  def serviceApplicationConfigFile(serviceName: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${githubConfig.githubToken}"))
    val requestUrl = url"${githubConfig.githubRawUrl}/hmrc/$serviceName/HEAD/conf/application.conf"
    doCall(requestUrl, newHc)
  }

  private def doCall(url: URL, newHc: HeaderCarrier) = {
    implicit val hc: HeaderCarrier = newHc
    httpClient.GET[HttpResponse](url).map {
      case response if response.status != 200 =>
        logger.warn(s"Failed with status code '${response.status}' to download config file from $url")
        "" // TODO fix this
      case response =>
        response.body
    }
  }
}
