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
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.{CommitId, Environment, FileName, ServiceName}

import java.net.URL
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ConfigConnector @Inject()(
  httpClientV2: HttpClientV2,
  githubConfig: GithubConfig
)(using
  ec: ExecutionContext
) extends Logging:

  def appConfigEnvYaml(env: Environment, serviceName: ServiceName, commitId: CommitId)(using hc: HeaderCarrier): Future[Option[String]] =
    doCall(url"${githubConfig.githubRawUrl}/hmrc/app-config-${env.asString}/${commitId.asString}/${serviceName.asString}.yaml")

  def appConfigBaseConf(serviceName: ServiceName, commitId: CommitId)(using hc: HeaderCarrier): Future[Option[String]] =
    doCall(url"${githubConfig.githubRawUrl}/hmrc/app-config-base/${commitId.asString}/${serviceName.asString}.conf")

  def appConfigCommonYaml(fileName: FileName, commitId: CommitId)(using hc: HeaderCarrier): Future[Option[String]] =
    val fn = fileName.asString.replace("api-", "") // fileName's with "api-" in them are symlinks, and will just return the name of the symlinked file rather than the content
    doCall(url"${githubConfig.githubRawUrl}/hmrc/app-config-common/${commitId.asString}/$fn")

  def applicationConf(serviceName: ServiceName, commitId: CommitId)(using hc: HeaderCarrier): Future[Option[String]] =
    doCall(url"${githubConfig.githubRawUrl}/hmrc/${serviceName.asString}/${commitId.asString}/conf/application.conf")

  private def doCall(url: URL)(using hc: HeaderCarrier) =
    httpClientV2
      .get(url)
      .setHeader(("Authorization", s"token ${githubConfig.githubToken}"))
      .withProxy
      .execute[HttpResponse]
      .map:
        case response if response.status == 200 =>
          Some(response.body)
        case response if response.status == 404 =>
          None
        case response =>
          sys.error(s"Failed with status code '${response.status}' to download config file from $url")
