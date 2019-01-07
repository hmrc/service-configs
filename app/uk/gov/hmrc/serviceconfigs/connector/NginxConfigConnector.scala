/*
 * Copyright 2019 HM Revenue & Customs
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
import play.Logger
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse}
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.serviceconfigs.model.NginxConfigFile
import uk.gov.hmrc.serviceconfigs.config.{GithubConfig, NginxConfig}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


class NginxConfigConnector @Inject()(http: HttpClient, gitConf: GithubConfig, nginxConfig: NginxConfig) {

  private implicit val httpReads: HttpReads[HttpResponse] = new HttpReads[HttpResponse] {
    override def read(method: String, url: String, response: HttpResponse): HttpResponse = response
  }

  private val configKey = gitConf.githubApiOpenConfig.key

  def configFor(env: String) : Future[Option[NginxConfigFile]] = {

    val url = s"${gitConf.githubRawUrl}/hmrc/${nginxConfig.configRepo}/master/$env/${nginxConfig.frontendConfigFile}"
    implicit val hc = HeaderCarrier().withExtraHeaders(("Authorization", s"token $configKey"))

    http.GET(url).map {
      case response: HttpResponse if response.status != 200 => {
        Logger.warn(s"Failed to download nginx config from ${url}, server returned ${response.status}")
        None
      }
      case response: HttpResponse => Option(NginxConfigFile(env, url, response.body))
    }
  }

}
