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

package uk.gov.hmrc.serviceconfigs.config

import java.io.File

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.githubclient.GitApiConfig

@Singleton
class GithubConfig @Inject()(configuration: Configuration) {

  val githubOpenConfigKey: String = "github.open.api"

  val githubApiUrl: String = configuration.get[String](s"$githubOpenConfigKey.apiurl")
  val githubRawUrl: String = configuration.get[String](s"$githubOpenConfigKey.rawurl")
  val host: Option[String] = configuration.getOptional[String](s"$githubOpenConfigKey.host")
  val user: Option[String] = configuration.getOptional[String](s"$githubOpenConfigKey.user")
  val key: Option[String] = configuration.getOptional[String](s"$githubOpenConfigKey.key")

  val githubApiOpenConfig: GitApiConfig =
    (user, key, host) match {
      case (Some(u), Some(k), Some(h)) => GitApiConfig(u, k, h)
      case (None, None, None) if new File(gitPath(".credentials")).exists() => GitApiConfig.fromFile(gitPath(".credentials"))
      case _ => GitApiConfig("user_not_set", "key_not_set", "http://127.0.0.1")
    }

  private def gitPath(gitFolder: String): String = s"${System.getProperty("user.home")}/.github/$gitFolder"

}
