/*
 * Copyright 2020 HM Revenue & Customs
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

import javax.inject.{Inject, Singleton}
import play.api.Logging
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.serviceconfigs.config.GithubConfig

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigConnector @Inject()(
  http          : HttpClient,
  servicesConfig: ServicesConfig,
  githubConf    : GithubConfig
)(implicit ec: ExecutionContext
) extends Logging {

  private implicit val httpReads: HttpReads[HttpResponse] = new HttpReads[HttpResponse] {
    override def read(method: String, url: String, response: HttpResponse): HttpResponse = response
  }

  private val configKey = githubConf.githubApiOpenConfig.key

  def serviceConfigYaml(env: String, service: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token $configKey"))
    val requestUrl = s"${githubConf.githubRawUrl}/hmrc/app-config-$env/master/$service.yaml"
    doCall(requestUrl, newHc)
  }

  def serviceConfigConf(env: String, service: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token $configKey"))
    val requestUrl = s"${githubConf.githubRawUrl}/hmrc/app-config-$env/master/$service.conf"
    doCall(requestUrl, newHc)
  }

  def serviceCommonConfigYaml(env: String, serviceType: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token $configKey"))
    val requestUrl = s"${githubConf.githubRawUrl}/hmrc/app-config-common/master/$env-$serviceType-common.yaml"
    doCall(requestUrl, newHc)
  }

  def serviceApplicationConfigFile(serviceName: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token $configKey"))
    val requestUrl = s"${githubConf.githubRawUrl}/hmrc/$serviceName/master/conf/application.conf"
    doCall(requestUrl, newHc)
  }

  private def doCall(url: String, newHc: HeaderCarrier) = {
    implicit val hc: HeaderCarrier = newHc
    http.GET(url).map {
      case response: HttpResponse if response.status != 200 =>
        logger.warn(s"Failed to download config file from $url")
        ""
      case response: HttpResponse =>
        response.body
    }
  }
}
