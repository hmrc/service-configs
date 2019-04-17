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

import javax.inject.{Inject, Singleton}
import play.Logger
import uk.gov.hmrc.http.{HeaderCarrier, HttpReads, HttpResponse, NotFoundException}
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.play.bootstrap.http.HttpClient
import uk.gov.hmrc.serviceconfigs.config.GithubConfig

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


case class DependencyConfig(
    group   : String
  , artefact: String
  , version : String
  , configs : Map[String, String]
  )

object DependencyConfig {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Reads, __}
  val reads: Reads[DependencyConfig] =
    ( (__ \ "group"   ).read[String]
    ~ (__ \ "artefact").read[String]
    ~ (__ \ "version" ).read[String]
    ~ (__ \ "configs" ).read[Map[String, String]]
    )(DependencyConfig.apply _)
}

case class Dependency(
    path    : String
  , group   : String
  , artefact: String
  , version : String
  )


case class SlugInfo(
    uri              : String
  , name             : String
  , version          : String
  , classpath        : String
  , dependencies     : List[Dependency]
  , applicationConfig: String
  , slugConfig       : String
  )

object SlugInfo {
  import play.api.libs.functional.syntax._
  import play.api.libs.json.{Reads, __}

  val reads: Reads[SlugInfo] = {
    implicit val dReads: Reads[Dependency] =
      ( (__ \ "path"    ).read[String]
      ~ (__ \ "group"   ).read[String]
      ~ (__ \ "artifact").read[String]
      ~ (__ \ "version" ).read[String]
      )(Dependency.apply _)

    ( (__ \ "uri"              ).read[String]
    ~ (__ \ "name"             ).read[String]
    ~ (__ \ "version"          ).read[String]
    ~ (__ \ "classpath"        ).read[String]
    ~ (__ \ "dependencies"     ).read[List[Dependency]]
    ~ (__ \ "applicationConfig").read[String]
    ~ (__ \ "slugConfig"       ).read[String]
    )(SlugInfo.apply _)
  }
}


@Singleton
class ConfigConnector @Inject()(
  http          : HttpClient,
  servicesConfig: ServicesConfig,
  gitConf       : GithubConfig
) {

  private implicit val httpReads: HttpReads[HttpResponse] = new HttpReads[HttpResponse] {
    override def read(method: String, url: String, response: HttpResponse): HttpResponse = response
  }

  private val configKey = gitConf.githubApiOpenConfig.key
  private val serviceDependenciesUrl: String = servicesConfig.baseUrl("service-dependencies")

  def serviceConfigYaml(env: String, service: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${configKey}"))
    val requestUrl = s"${gitConf.githubRawUrl}/hmrc/app-config-$env/master/$service.yaml"
    doCall(requestUrl, newHc)
  }

  def serviceConfigConf(env: String, service: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${configKey}"))
    val requestUrl = s"${gitConf.githubRawUrl}/hmrc/app-config-$env/master/$service.conf"
    doCall(requestUrl, newHc)
  }

  def slugDependencyConfigs(service: String)(implicit hc: HeaderCarrier): Future[List[DependencyConfig]] = {
    implicit val dcr = DependencyConfig.reads
    http.GET[List[DependencyConfig]](s"$serviceDependenciesUrl/api/slugDependencyConfigs?name=$service&flag=latest")
      .recover { case ex: NotFoundException => Nil }
  }

  def slugInfo(service: String)(implicit hc: HeaderCarrier): Future[Option[SlugInfo]] = {
    implicit val sir = SlugInfo.reads
    http.GET[SlugInfo](s"$serviceDependenciesUrl/api/sluginfo?name=$service&flag=latest")
      .map(Some.apply)
      .recover { case ex: NotFoundException => None }
  }

  def serviceCommonConfigYaml(env: String, serviceType: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${configKey}"))
    val requestUrl = s"${gitConf.githubRawUrl}/hmrc/app-config-common/master/$env-$serviceType-common.yaml"
    doCall(requestUrl, newHc)
  }

  def serviceApplicationConfigFile(serviceName: String)(implicit hc: HeaderCarrier): Future[String] = {
    val newHc      = hc.withExtraHeaders(("Authorization", s"token ${configKey}"))
    val requestUrl = s"${gitConf.githubRawUrl}/hmrc/$serviceName/master/conf/application.conf"
    doCall(requestUrl, newHc)
  }

  private def doCall(url: String, newHc: HeaderCarrier) = /*scala.util.Try*/ {
    implicit val hc: HeaderCarrier = newHc
    http.GET(url).map {
      case response: HttpResponse if response.status != 200 =>
        Logger.warn(s"Failed to download config file from $url: ${response.status}")
        ""
      case response: HttpResponse =>
        response.body
    }.recover {
      case e => Logger.error(s"1) Failed to download config file from $url: ${e.getMessage}", e); throw e
    }
  }//.recover { case e => Logger.error(s"2) Failed to download config file from $url: ${e.getMessage}", e); throw e }.get
}
