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

import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.serviceconfigs.ConfigService._
import play.api.libs.functional.syntax._

trait ConfigJson {

  implicit val configEntryReads = Json.reads[ConfigByEnvEntry]
  implicit val configEntryWrites = Json.writes[ConfigByEnvEntry]

  implicit val environmentConfigEntryWrites = Json.writes[ConfigByKeyEntry]

  implicit val configSourceWrites = new Writes[ConfigSource] {
    def writes(configSource: ConfigSource) = Json.obj(
      "name" -> configSource.name
    )
  }

  implicit val environmentWrites = new Writes[Environment] {
    def writes(env: Environment) = Json.obj(
      "name" -> env.name
    )
  }

  implicit val environmentConfigSourceWrites = new Writes[EnvironmentConfigSource] {
    def writes(ecs: EnvironmentConfigSource) = Json.obj(
      "environment" -> ecs._1.name,
      "configSource" -> ecs._2.name
    )
  }

  implicit val configByEnvironmentWrites = new Writes[ConfigByEnvironment] {
    def writes(cbe: ConfigByEnvironment) = Json.toJson(cbe.map(e => e._1._1.name -> e._2))
  }

}
