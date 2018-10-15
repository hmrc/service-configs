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

import org.scalatest.Matchers._
import org.scalatest.WordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.serviceconfigs.ConfigService._

class JsonMarshallingSpec extends WordSpec with ConfigJson {


  "A ConfigSource case object" should {
    "unmarshall BaseConfig" in {
      Json.toJson(BaseConfig("test")) shouldBe Json.parse("""{"name":"test"}""")
    }

    "Environment" should {
      "unmarshal" in {
        Json.toJson(Environment("envtest", Seq(BaseConfig("basetest")))) shouldBe Json.parse(
          """
            |{"name":"envtest",
            |"configs":[{
            |"name":"basetest"
            |}]
            |}
            |""".stripMargin)
      }
    }

    "ConfigByEnvironment" should {
      "unmarshal" in {
        val environment = Environment("environment", Seq(BaseConfig("configSource-base")))
        val configSource = BaseConfig("configSource-base")
        val ecs = new EnvironmentConfigSource(environment, configSource)
        val cbe = Map(ecs -> Map("entry1" -> ConfigEntry("configEntry-1")))

        Json.toJson(cbe) shouldBe Json.parse(
          """
            |[[
            |{"environment":{
            |"name":"environment",
            |"configs":[
            |{"name":"configSource-base"}
            |]},
            |"conifigSource":
            |{"name":"configSource-base"}
            |},
            |{"entry1":{
            |"value":"configEntry-1"
            |}
            |}
            |]]""".stripMargin)
      }
    }
  }
}
