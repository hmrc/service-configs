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

import javax.inject.{Inject, Singleton}
import play.api.Logging
import play.api.libs.json.{JsValue, Reads, __}
import play.api.libs.functional.syntax._
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.serviceconfigs.model.AdminFrontendRoute
import uk.gov.hmrc.serviceconfigs.util.YamlUtil.fromYaml

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RoutesConfigConnector @Inject()(
  httpClientV2: HttpClientV2
, githubConfig: GithubConfig
)(implicit ec: ExecutionContext
) extends Logging {
  private implicit val hc = HeaderCarrier()

  private def readsAdminFrontendRoute(route: String, lines: List[String]): Reads[AdminFrontendRoute] =
    ( (__ \ "microservice").read[String]
    ~ Reads.pure(route)
    ~ (__ \ "allow").read[Map[String, List[String]]]
    ~ Reads.pure(s"https://github.com/hmrc/admin-frontend-proxy/blob/HEAD/config/routes.yaml#L${lines.indexOf(route + ":") + 1}")
    )(AdminFrontendRoute.apply _)

  def allAdminFrontendRoutes(): Future[Seq[AdminFrontendRoute]] = {
    val url = url"${githubConfig.githubRawUrl}/hmrc/admin-frontend-proxy/HEAD/config/routes.yaml"
    httpClientV2
      .get(url)
      .setHeader("Authorization" -> s"token ${githubConfig.githubToken}")
      .withProxy
      .execute[HttpResponse]
      .map {
        case rsp if rsp.status != 200 => sys.error(s"Failed to download Admin Routes $url, server returned ${rsp.status}")
        case rsp                      => rsp.body
      }
      .map { body =>
        val lines = body.linesIterator.toList.map(_.replaceAll(" ", ""))
        fromYaml[Map[String, JsValue]](body)
          .map { case (k, v) => v.as[AdminFrontendRoute](readsAdminFrontendRoute(k, lines)) }
          .toSeq
      }
  }
}
