/*
 * Copyright 2021 HM Revenue & Customs
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

import org.mongodb.scala.bson.{BsonArray, BsonDocument, BsonNumber, BsonString, BsonValue}

import java.util
import scala.collection.mutable
import scala.util.Try

object YamlToBson {

  import scala.collection.JavaConverters._

  def apply(data: mutable.Map[String, Object]): Try[BsonDocument] = Try {
    BsonDocument(data.map(kv => escapeKey(kv._1) -> toBson(kv._2)))
  }

  // bson keys can't have . in them. we shouldn't expect to see any outside the hmrc_config block but just in case, escape the .
  private def escapeKey(key: String): String = key.replaceAll("\\.", "\\\\.")

  private def toBson(v: Object): BsonValue =
    v match {
      case v: util.LinkedHashMap[String, Object] => BsonDocument(v.asScala.map(kv => kv._1 -> toBson(kv._2)))
      case v: util.ArrayList[Object]             => BsonArray.fromIterable(v.asScala.map(toBson))
      case v: Integer                            => BsonNumber(v)
      case v                                     => BsonString(v.toString)
    }
}
