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

package uk.gov.hmrc.serviceconfigs

import play.api.libs.json.{Writes, __}
import play.api.libs.functional.syntax._
import uk.gov.hmrc.serviceconfigs.service.ConfigService._

trait ConfigJson {
  implicit val configSourceEntriesWrites: Writes[ConfigSourceEntries] = {
    ( (__ \ "source" ).write[String]
      ~ (__ \ "entries").write[Map[KeyName, String]].contramap[Map[KeyName, String]]( m => m.map {
      case (k, v) if v.startsWith("ENC[") => k -> "ENC[...]"
      case (k, v)                         => k -> v
    })
      )(unlift(ConfigSourceEntries.unapply))
  }

  implicit val configSourceValueWrites: Writes[ConfigSourceValue] =
    ( (__ \ "source").write[String]
    ~ (__ \ "value" ).write[String].contramap[String](s => if (s.startsWith("ENC[")) "ENC[...]" else s)
    )(unlift(ConfigSourceValue.unapply))
}
