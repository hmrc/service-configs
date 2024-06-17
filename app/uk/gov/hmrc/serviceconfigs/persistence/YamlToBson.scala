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

package uk.gov.hmrc.serviceconfigs.persistence

import org.mongodb.scala.bson.{BsonArray, BsonDocument, BsonString, BsonValue}

import java.util
import scala.collection.mutable
import scala.util.Try

object YamlToBson:

  import scala.jdk.CollectionConverters._

  def apply(data: mutable.Map[String, Object]): Try[BsonDocument] =
    Try(BsonDocument(
      data.map:
        case (k, v) => escapeKey(k) -> toBson(v)
    ))

  // bson keys can't have . in them. we shouldn't expect to see any outside the hmrc_config block but just in case, escape the .
  private def escapeKey(key: String): String =
    key.replaceAll("\\.", "\\\\.")

  private def toBson(v: Object): BsonValue =
    v match
      case v: util.LinkedHashMap[String, Object] => BsonDocument(v.asScala.map { case (k, v) => k -> toBson(v) })
      case v: util.ArrayList[Object]             => BsonArray.fromIterable(v.asScala.map(toBson))
      case v                                     => BsonString(v.toString)
