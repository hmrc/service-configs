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

package uk.gov.hmrc.serviceconfigs.model

import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, __}

import java.time.LocalDate

case class BobbyRules(
  libraries: Seq[BobbyRule],
  plugins  : Seq[BobbyRule]
)

object BobbyRules {

  private def bobbyRuleFormat(implicit dateFormat: Format[LocalDate]): Format[BobbyRule] =
    ( (__ \ "organisation"  ).format[String]
    ~ (__ \ "name"          ).format[String]
    ~ (__ \ "range"         ).format[String]
                             .inmap[String](
                               range => if (range.trim == "*") "[0.0.0,)" else range,
                               identity
                             )
    ~ (__ \ "reason"        ).format[String]
    ~ (__ \ "from"          ).format[LocalDate]
    ~ (__ \ "exemptProjects").formatWithDefault[Seq[String]](Seq.empty)
    )(BobbyRule.apply, pt => Tuple.fromProductTyped(pt))

  val mongoFormat: Format[BobbyRules] = {
    given Format[BobbyRule] = bobbyRuleFormat(uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats.localDateFormat)
    ( (__ \ "libraries").format[Seq[BobbyRule]]
    ~ (__ \ "plugins"  ).format[Seq[BobbyRule]]
    )(BobbyRules.apply, pt => Tuple.fromProductTyped(pt))
  }

  val apiFormat: Format[BobbyRules] = {
    given Format[BobbyRule] = bobbyRuleFormat(implicitly[Format[LocalDate]])
    ( (__ \ "libraries").format[Seq[BobbyRule]]
    ~ (__ \ "plugins"  ).format[Seq[BobbyRule]]
    )(BobbyRules.apply, pt => Tuple.fromProductTyped(pt))
  }
}


case class BobbyVersion(
  version  : Version,
  inclusive: Boolean
)


final case class BobbyRule(
  organisation: String,
  name        : String,
  range       : String,
  reason      : String,
  from        : LocalDate,
  exemptProjects: Seq[String] = Seq.empty
)
