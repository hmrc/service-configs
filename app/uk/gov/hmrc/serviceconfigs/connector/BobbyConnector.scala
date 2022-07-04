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

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.Configuration
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.StringContextOps

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyConnector @Inject()(
  httpClientV2: HttpClientV2,
  githubConfig: GithubConfig,
  bobbyConfig : BobbyConfig
)( implicit ec: ExecutionContext
) extends Logging {

  private implicit val hc: HeaderCarrier =
      HeaderCarrier()

  def findAllRules(): Future[String] = {
    val url = url"${bobbyConfig.url}"
    httpClientV2
      .get(url)
      .replaceHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .execute[HttpResponse]
      .map {
        case response if response.status != 200 =>
          sys.error(s"Failed to download Bobby rules $url, server returned ${response.status}")
        case response => response.body
      }

  }
}

@Singleton
class BobbyConfig @Inject()(config: Configuration) {
  val url: String = config.get[String]("bobby.url")
}
