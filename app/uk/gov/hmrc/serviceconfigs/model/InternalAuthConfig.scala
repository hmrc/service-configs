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

import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue, Reads, __}
import play.api.libs.functional.syntax._

case class GrantGroup(
  services: Seq[String],
  grant   : GrantType
)

object GrantGroup:
  val grantGroupReads: Reads[Option[GrantGroup]] =
    (__ \ "grantees" \ "service")
      .read[Seq[String]]
      .map(v => Some(GrantGroup(v, GrantType.Grantee)))
      .or((__ \ "resourceType").read[String].map(v => Some(GrantGroup(List(v), GrantType.Grantor))))
      .or(Reads.pure(None))

case class InternalAuthConfig(
  serviceName: ServiceName,
  environment: InternalAuthEnvironment,
  grantType  : GrantType
)

object InternalAuthConfig:
  val format: Format[InternalAuthConfig] =
    ( (__ \ "serviceName").format[ServiceName](ServiceName.format)
    ~ (__ \ "environment").format[InternalAuthEnvironment](InternalAuthEnvironment.format)
    ~ (__ \ "grantType"  ).format[GrantType](GrantType.format)
    )(InternalAuthConfig.apply, pt => Tuple.fromProductTyped(pt))

enum GrantType(val asString: String):
  case Grantee extends GrantType("grantee")
  case Grantor extends GrantType("grantor")

object GrantType:
  val format: Format[GrantType] =
    new Format[GrantType]:
      override def writes(grantType: GrantType): JsValue =
        JsString(grantType.asString)

      override def reads(json: JsValue): JsResult[GrantType] =
        json
          .validate[String]
          .flatMap:
            case "grantee" => JsSuccess(Grantee)
            case "grantor" => JsSuccess(Grantor)
            case _         => JsError("Invalid Grant Type")

enum InternalAuthEnvironment(val asString: String):
  case Prod extends InternalAuthEnvironment("production")
  case Qa   extends InternalAuthEnvironment("qa")

object InternalAuthEnvironment:
  val format: Format[InternalAuthEnvironment] =
    new Format[InternalAuthEnvironment]:
      override def writes(o: InternalAuthEnvironment): JsValue =
        JsString(o.asString)

      override def reads(json: JsValue): JsResult[InternalAuthEnvironment] =
        json
          .validate[String]
          .flatMap:
            case "production" => JsSuccess(Prod)
            case "qa"         => JsSuccess(Qa)
            case _            => JsError("Invalid Internal Auth Environment")
