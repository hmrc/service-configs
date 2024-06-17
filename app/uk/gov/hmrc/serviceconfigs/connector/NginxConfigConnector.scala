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

import play.api.Logging
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.serviceconfigs.config.{GithubConfig, NginxConfig}
import uk.gov.hmrc.serviceconfigs.model.{Environment, NginxConfigFile}
import uk.gov.hmrc.http.StringContextOps

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class NginxConfigConnector @Inject()(
  httpClientV2: HttpClientV2,
  githubConfig: GithubConfig,
  nginxConfig : NginxConfig
)(using
  ec: ExecutionContext
) extends Logging:

  private given HeaderCarrier = HeaderCarrier()

  def getNginxRoutesFile(fileName: String, environment: Environment): Future[NginxConfigFile] =
    val url = url"${githubConfig.githubRawUrl}/hmrc/${nginxConfig.configRepo}/${nginxConfig.configRepoBranch}/${environment.asString}/$fileName"
    httpClientV2
      .get(url)
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .execute[HttpResponse]
      .map:
        case response if response.status != 200 =>
          sys.error(s"Failed to download nginx config from $url, server returned ${response.status}")
        case response =>
          logger.info(s"Retrieved Nginx routes file at $url")
          NginxConfigFile(environment, url.toString, response.body, branch = nginxConfig.configRepoBranch)
