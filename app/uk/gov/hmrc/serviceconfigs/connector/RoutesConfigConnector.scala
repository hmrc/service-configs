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
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.serviceconfigs.model.AdminFrontendRoute

import scala.concurrent.{ExecutionContext, Future}

object RoutesConfigConnector {
  import play.api.libs.functional.syntax._
  import play.api.libs.json._

  case class RawAdminFrontendRoute(
    service : String
  , route   : String
  , allow   : Map[String, List[String]]
  )

  def readsRawAdminFrontendRoute(key: String): Reads[RawAdminFrontendRoute] =
    ( (__ \ "microservice").read[String]
    ~ Reads.pure(key)
    ~ (__ \ "allow"       ).read[Map[String, List[String]]]
    )(RawAdminFrontendRoute.apply _)
}

@Singleton
class RoutesConfigConnector @Inject()(
  httpClientV2: HttpClientV2
, githubConfig: GithubConfig
)(implicit ec: ExecutionContext
) extends Logging {
  import RoutesConfigConnector._

  private implicit val hc = HeaderCarrier()

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
        yamlToJson(body)
          .as[Map[String, play.api.libs.json.JsValue]]
          .map { case (k, v) => v.as[RawAdminFrontendRoute](readsRawAdminFrontendRoute(k)) }
          .map { raw => AdminFrontendRoute(
            service  = raw.service
          , route    = raw.route
          , allow    = raw.allow
          , location = s"https://github.com/hmrc/admin-frontend-proxy/blob/HEAD/config/routes.yaml#L${lines.indexOf(raw.route + ":") + 1}"
          )}.toSeq
      }
  }

  import play.api.libs.json.{Json, JsValue}
  import com.fasterxml.jackson.databind.ObjectMapper
  import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
  def yamlToJson(yaml: String): JsValue = {
    val yamlReader = new ObjectMapper(new YAMLFactory())
    val jsonWriter = new ObjectMapper()
    Json.parse(jsonWriter.writeValueAsString(yamlReader.readValue(yaml, classOf[Object])))
  }
}
