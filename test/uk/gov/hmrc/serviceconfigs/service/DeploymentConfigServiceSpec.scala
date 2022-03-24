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

package uk.gov.hmrc.serviceconfigs.service

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.yaml.snakeyaml.Yaml
import uk.gov.hmrc.serviceconfigs.model.Environment.Production

import java.util
import scala.collection.mutable
import scala.jdk.CollectionConverters._

class DeploymentConfigServiceSpec extends AnyWordSpecLike with Matchers {

  import DeploymentConfigService._

  "isAppConfig" must {
    "reject non yaml" in {
      isAppConfig("/test/helloworld.txt") mustBe false
      isAppConfig("/app-config-production/foo/bar.xml") mustBe false
      isAppConfig("/app-config-production/.yaml/not-a-yaml.json") mustBe false
    }

    "reject files in the ignore list" in {
      isAppConfig("/app-config-production/repository.yaml") mustBe false
      isAppConfig("/app-config-production/.github/stale.yaml") mustBe false
    }

    "accept any other yaml file" in {
      isAppConfig("/app-config-production/test.yaml") mustBe true
      isAppConfig("/app-config-production/auth.yaml") mustBe true
    }
  }

  "modifyConfigKeys" must {

    "discard the 0.0.0 root element, hmrc_config and add name and environment" in {
      val yaml =
        s"""
           |0.0.0:
           |  type: frontend
           |  slots: 1
           |  instances: 1
           |  hmrc_config:
           |    foo: 'true'
           |    bar: 'false'
           |  zone: public
           |""".stripMargin

      val data = new Yaml().load(yaml).asInstanceOf[util.LinkedHashMap[String, Object]].asScala
      val result = modifyConfigKeys(data, "test", Production)

      result.isDefined mustBe true
      result.get.keySet mustBe Set("slots", "instances", "zone", "name", "environment", "type")
    }

    "return None when there is no 0.0.0 root element" in {
      val data: mutable.Map[String, Object] = mutable.Map[String, Object]("any-other-key" -> new mutable.LinkedHashMap[String,Object]())
      val result = modifyConfigKeys(data, "test", Production)
      result mustBe None
    }

    "returns None when the config is missing required keys" in {
      val yaml =
        s"""
           |0.0.0:
           |  type: frontend
           |  slots: 1
           |  instances: 1
           |""".stripMargin

      val data = new Yaml().load(yaml).asInstanceOf[util.LinkedHashMap[String, Object]].asScala
      val result = modifyConfigKeys(data, "test", Production)
      result mustBe None
    }
  }
}
