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

package uk.gov.hmrc.serviceconfigs.controller

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.serviceconfigs.parser.ConfigValue
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigEnvironment, ConfigSourceEntries}

class ConfigControllerJsonMarshallingSpec extends AnyWordSpec with Matchers:
  "ConfigByEnvironment" should:
    "unmarshal" in:
      val cbe: Map[ConfigEnvironment, Seq[ConfigSourceEntries]] = Map(
        ConfigEnvironment.Local -> Seq(
          ConfigSourceEntries(
            source     = "baseConfig",
            sourceUrl  = None,
            entries    = Map(
                           "entry1" -> ConfigValue("configEntry-1"),
                           "entry2" -> ConfigValue("configEntry-2")
                         )
          ),
          ConfigSourceEntries(
            source    = "appConfig",
            sourceUrl = Some("https://github.com/hmrc/appConfig"),
            entries   = Map(
                          "entry3" -> ConfigValue("configEntry-3")
                        )
          )
        )
      )

      given Writes[Map[ConfigEnvironment, Seq[ConfigSourceEntries]]] = ConfigController.mapWrites

      Json.toJson(cbe) shouldBe Json.parse("""
        {
          "local":[
            {
              "source": "baseConfig",
              "entries": {
                "entry1": "configEntry-1",
                "entry2": "configEntry-2"
              }
            },
            {
              "source": "appConfig",
              "sourceUrl": "https://github.com/hmrc/appConfig",
              "entries": {
                "entry3": "configEntry-3"
              }
            }
          ]
        }
      """)
