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

sealed trait FilterType {val asString: String }

object FilterType {

  case object Contains                 extends FilterType { val asString = "contains"                }
  case object ContainsIgnoreCase       extends FilterType { val asString = "containsIgnoreCase"      }
  case object DoesNotContain           extends FilterType { val asString = "doesNotContain"          }
  case object DoesNotContainIgnoreCase extends FilterType { val asString = "doesNotContainIgnoreCase"}
  case object EqualTo                  extends FilterType { val asString = "equalTo"                 }
  case object EqualToIgnoreCase        extends FilterType { val asString = "equalToIgnoreCase"       }
  case object NotEqualTo               extends FilterType { val asString = "notEqualTo"              }
  case object NotEqualToIgnoreCase     extends FilterType { val asString = "notEqualToIgnoreCase"    }
  case object IsEmpty                  extends FilterType { val asString = "isEmpty"                 }

  val values: List[FilterType] =
    List(Contains, ContainsIgnoreCase, DoesNotContain, DoesNotContainIgnoreCase, EqualTo, EqualToIgnoreCase, NotEqualTo, NotEqualToIgnoreCase, IsEmpty)

  def parse(s: String): Option[FilterType] =
    values.find(_.asString == s)

}
