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

case class Services (serviceName: Seq[String], grant: GrantType)

object Services {
  val serviceReads: Reads[Option[Services]] = (
    (__ \ "grantees" \ "service").read[Seq[String]].map(v => Some(Services(v, GrantType.Grantee))) or
      (__ \ "resourceType").read[String].map(v => Some(Services(List(v), GrantType.Grantor))) or
      Reads.pure(None)
    )
}

case class InternalAuthConfig(service: ServiceName, environment: InternalAuthEnvironment, grantType: GrantType)

object InternalAuthConfig {
  val format: Format[InternalAuthConfig] = {
    implicit val snf = ServiceName.format
    ((__ \ "serviceName").format[ServiceName]
      ~ (__ \ "environment").format[InternalAuthEnvironment]
      ~ (__ \ "grantType").format[GrantType]
      )(InternalAuthConfig.apply, unlift(InternalAuthConfig.unapply))
  }
  

}

sealed trait GrantType{ def asString: String }

object GrantType {

  case object Grantee extends GrantType {
    val asString = "grantee"
  }

  case object Grantor extends GrantType {
    val asString = "grantor"
  }

  implicit val format: Format[GrantType] = new Format[GrantType] {
    override def writes(grantType: GrantType): JsValue = JsString(grantType.asString)

    override def reads(json: JsValue): JsResult[GrantType] = json match {
      case JsString("grantee") => JsSuccess(Grantee)
      case JsString("grantor") => JsSuccess(Grantor)
      case _ => JsError("Invalid grant type")
    }
  }
}

sealed trait InternalAuthEnvironment{ def asString: String }

object InternalAuthEnvironment {

  case object Prod extends InternalAuthEnvironment {
    val asString = "production"
  }

  case object Qa extends InternalAuthEnvironment {
    val asString = "qa"
  }

 implicit val format: Format[InternalAuthEnvironment] = new Format[InternalAuthEnvironment] {
    override def writes(o: InternalAuthEnvironment): JsValue = JsString(o.asString)

   //todo make this correct
    override def reads(json: JsValue): JsResult[InternalAuthEnvironment] = json match {
      case JsString("production") => JsSuccess(Prod)
      case JsString("qa") => JsSuccess(Qa)
      case _ => JsError("Invalid Internal Auth Environment")
    }
  }
}
