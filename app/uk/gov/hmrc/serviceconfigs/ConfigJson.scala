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

trait ConfigJson {

  implicit val configWrites = new Writes[ConfigSource] {
    def writes(configSource: ConfigSource) = Json.obj(
      "name" -> configSource.name
    )
  }

  implicit val configEntryReads = Json.reads[ConfigEntry]
  implicit val configEntryWrites = Json.writes[ConfigEntry]
  implicit val environmentWrites = Json.writes[Environment]

  implicit val environmentConfigSourceWrites = new Writes[EnvironmentConfigSource] {
    def writes(ecs: EnvironmentConfigSource) = Json.obj(
      "environment" -> Json.toJson(ecs._1),
      "conifigSource" -> Json.toJson(ecs._2)
    )
  }

}
