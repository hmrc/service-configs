/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs

import com.google.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.githubclient.GitApiConfig

@Singleton
class GithubConfig @Inject()(configuration: Configuration) {
  val githubOpenConfigKey = "github.open.api"

  val githubApiOpenConfig = getGitApiConfig(githubOpenConfigKey).getOrElse(GitApiConfig.fromFile(gitPath(".credentials")))

  private def gitPath(gitFolder: String): String =
    s"${System.getProperty("user.home")}/.github/$gitFolder"

  private def getGitApiConfig(base: String): Option[GitApiConfig] =
    for {
      user <- configuration.getOptional[String](s"$base.user")
      key  <- configuration.getOptional[String](s"$base.key")
      host <- configuration.getOptional[String](s"$base.host")
    } yield GitApiConfig(user, key, host)

}
