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

import javax.inject.Inject
import play.api.Logging
import uk.gov.hmrc.http.{HeaderCarrier, HttpClient, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.serviceconfigs.config.{GithubConfig, NginxConfig}
import uk.gov.hmrc.serviceconfigs.model.NginxConfigFile
import uk.gov.hmrc.http.StringContextOps

import scala.concurrent.{ExecutionContext, Future}

class NginxConfigConnector @Inject()(
  httpClient  : HttpClient,
  githubConfig: GithubConfig,
  nginxConfig : NginxConfig
)(implicit ec: ExecutionContext
) extends Logging {

  def getNginxRoutesFile(fileName: String, environment: String): Future[NginxConfigFile] = {
    val url =
      url"${githubConfig.githubRawUrl}/hmrc/${nginxConfig.configRepo}/${nginxConfig.configRepoBranch}/$environment/$fileName"

    implicit val hc: HeaderCarrier =
      HeaderCarrier().withExtraHeaders(("Authorization", s"token ${githubConfig.githubToken}"))

    httpClient.GET[HttpResponse](url).map {
      case response if response.status != 200 =>
        sys.error(s"Failed to download nginx config from $url, server returned ${response.status}")
      case response =>
        logger.info(s"Retrieved Nginx routes file at $url")
        NginxConfigFile(environment, url.toString, response.body, branch = nginxConfig.configRepoBranch)
    }
  }
}
