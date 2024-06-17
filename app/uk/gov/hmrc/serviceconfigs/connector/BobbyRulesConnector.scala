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
import play.api.libs.json.Format
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.BobbyRules
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.StringContextOps

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyRulesConnector @Inject()(
  httpClientV2: HttpClientV2,
  githubConfig: GithubConfig,
  config      : Configuration
)(using
  ec: ExecutionContext
) extends Logging:

  private given HeaderCarrier = HeaderCarrier()

  private val bobbyRulesUrl: String =
    config.get[String]("bobby.url")

  def findAllRules(): Future[BobbyRules] =
    given Format[BobbyRules] = BobbyRules.apiFormat
    httpClientV2
      .get(url"$bobbyRulesUrl")
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .execute[BobbyRules]
