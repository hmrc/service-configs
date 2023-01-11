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

package uk.gov.hmrc.serviceconfigs.config

import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class GithubConfig @Inject()(configuration: Configuration) {
  val githubApiUrl: String = configuration.get[String]("github.open.api.apiurl")
  val githubRawUrl: String = configuration.get[String]("github.open.api.rawurl")
  val githubToken : String = configuration.get[String]("github.open.api.key")
}
