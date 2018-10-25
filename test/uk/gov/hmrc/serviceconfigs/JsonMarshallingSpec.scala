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

import scala.collection.SortedMap

class JsonMarshallingSpec extends WordSpec with ConfigJson {


  "A ConfigSource case object" should {
    "unmarshall BaseConfig" in {
      Json.toJson(BaseConfig("test")) shouldBe Json.parse("""{"name":"test"}""")
    }
  }

  "Environment" should {
    "unmarshal" in {
      Json.toJson(Environment("envtest", Seq(BaseConfig("basetest")))) shouldBe Json.parse(
        """
          |{"name":"envtest"}
          |""".stripMargin)
    }
  }

  "EnvironmentConfigSource" should {
    "unmarshal" in {
      val configSource = BaseConfig("configSource-base")
      val environment = Environment("environmentName", Seq(BaseConfig("configSource-base")))
      val ecs = new EnvironmentConfigSource(environment, configSource)
      Json.toJson(ecs) shouldBe Json.parse(
        """|{
            |"environment":"environmentName",
            |"configSource":"configSource-base"
           |}""".stripMargin
      )
    }
  }

  "ConfigByEnvironment" should {
    "unmarshal" in {
      val baseSource = BaseConfig("configSource-base")
      val appConfigSource = AppConfig("configSource-appConfig")

      val environment = Environment("environmentName", Seq(baseSource, appConfigSource))

      val baseECS = new EnvironmentConfigSource(environment, baseSource)
      val appConfigECS = new EnvironmentConfigSource(environment, appConfigSource)

      val cbe = Map(
        baseECS -> Map("entry1" -> ConfigByEnvEntry("configEntry-1")),
        appConfigECS -> Map("entry2" -> ConfigByEnvEntry("configEntry-2")))

      Json.toJson(cbe) shouldBe Json.parse(
        """{
            |"environmentName":{
              |"configSource-base":{
                |"entry1":{"value":"configEntry-1"}
              |},
              |"configSource-appConfig":{
                |"entry2":{"value":"configEntry-2"}
              |}
            |}
           |}""".stripMargin
        )
    }
  }
  // type ConfigByKey = SortedMap[String, Map[EnvironmentConfigSource, ConfigEntry]]
  "ConfigByKey" should {
    "write to json" in {
      val configSource = BaseConfig("configSource-base")
      val environment = Environment("environmentName", Seq(BaseConfig("configSource-base")))
      val configByKey = Map("key1" -> Seq(ConfigByKeyEntry(environment.name, configSource.name, "configEntry1")) )

      Json.toJson(configByKey) shouldBe Json.parse(
        """
          |{
            |"key1":[
              |{
                |"environment":"environmentName",
                |"configSource":"configSource-base",
                |"value":"configEntry1"
              |}
            |]
          |}
        """.stripMargin
      )
    }
  }

}
