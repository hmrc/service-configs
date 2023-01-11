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

import play.api.libs.json.Json
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import uk.gov.hmrc.serviceconfigs.service.ConfigService
import uk.gov.hmrc.serviceconfigs.service.ConfigService._

class JsonMarshallingSpec extends AnyWordSpec with Matchers with ConfigJson {

  "ConfigByEnvironment" should {
    "unmarshal" in {
      val cbe: ConfigByEnvironment = Map(
        "environmentName" -> Seq(
          ConfigSourceEntries("baseConfig", Map(
            "entry1" -> "configEntry-1",
            "entry2" -> "configEntry-2"
          )),
          ConfigSourceEntries("appConfig", Map(
            "entry3" -> "configEntry-3"
          ))
        )
      )

      Json.toJson(cbe) shouldBe Json.parse("""
        {
          "environmentName":[
            {
              "source": "baseConfig",
              "entries": {
                "entry1": "configEntry-1",
                "entry2": "configEntry-2"
              }
            },
            {
              "source": "appConfig",
              "entries": {
                "entry3": "configEntry-3"
              }
            }
          ]
        }
      """)
    }
  }

  // type ConfigByKey = SortedMap[String, Map[EnvironmentConfigSource, ConfigEntry]]
  "ConfigByKey" should {
    "write to json" in {
      val configByKey = Map("key1" ->
        Map("environmentName" ->
          Seq(
            ConfigService.ConfigSourceValue("baseConfig", "configEntry1"),
            ConfigService.ConfigSourceValue("appConfig" , "configEntry2")
          )
        )
      )

      Json.toJson(configByKey) shouldBe Json.parse("""
        {
          "key1": {
            "environmentName": [
              {
                "source": "baseConfig",
                "value": "configEntry1"
              },
              {
                "source": "appConfig",
                "value": "configEntry2"
              }
            ]
          }
        }
      """)
    }
  }
}
