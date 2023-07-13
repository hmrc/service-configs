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

package uk.gov.hmrc.serviceconfigs

import play.api.libs.json.{Writes, __}
import play.api.libs.functional.syntax._
import uk.gov.hmrc.serviceconfigs.service.ConfigService._
import uk.gov.hmrc.serviceconfigs.parser.MyConfigValue

trait ConfigJson {
  implicit val configSourceEntriesWrites: Writes[ConfigSourceEntries] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "entries"  ).write[Map[KeyName, String]]
                        .contramap[Map[KeyName, MyConfigValue]](_.view.mapValues(_.render).toMap)
    )(unlift(ConfigSourceEntries.unapply))

  implicit val configSourceValueWrites: Writes[ConfigSourceValue] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "value"    ).write[String]
                        .contramap[MyConfigValue](_.render)
    )(unlift(ConfigSourceValue.unapply))
}
