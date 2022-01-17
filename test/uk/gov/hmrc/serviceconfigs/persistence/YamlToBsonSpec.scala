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

package uk.gov.hmrc.serviceconfigs.persistence

import java.util

import org.mongodb.scala.bson.{BsonArray, BsonDocument, BsonString}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.yaml.snakeyaml.Yaml

class YamlToBsonSpec extends AnyWordSpecLike with Matchers {

  val yaml =
    """
      |root:
      |  map:
      |    bar: hello
      |  instances: 2
      |  slots: 4
      |  list:
      |    - a
      |    - b
      |
      |""".stripMargin

  val bson = BsonDocument(
    "root" -> BsonDocument(
      "list"      -> BsonArray.fromIterable(Seq(BsonString("a"), BsonString("b"))),
      "instances" -> BsonString("2"),  // numbers are saved as strings, conversions should be handled in the read model
      "slots"     -> BsonString("4"),
      "map"       -> BsonDocument("bar" -> BsonString("hello"))
    )
  )

  "YamlToBson" must {
    import collection.JavaConverters._

    "convert yaml to bson" in {
      val data   = new Yaml().load(yaml).asInstanceOf[util.LinkedHashMap[String, Object]].asScala
      val result = YamlToBson(data)
      result.isSuccess mustBe true
      result.get mustBe bson
    }

    "escapes dots in keynames" in {
      val data   = new Yaml().load("dot.dot: dash").asInstanceOf[util.LinkedHashMap[String, Object]].asScala
      val result = YamlToBson(data)
      result.isSuccess mustBe true
      result.get mustBe BsonDocument("dot\\.dot" -> BsonString("dash"))
    }
  }
}
