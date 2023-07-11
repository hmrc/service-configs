package uk.gov.hmrc.serviceconfigs.model

import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue, __}
import play.api.libs.functional.syntax._


case class InternalAuthConfig(serviceName: ServiceName, environment: InternalAuthEnvironment, grantType: GrantType)

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
    val asString = "PROD"
  }

  case object Qa extends InternalAuthEnvironment {
    val asString = "QA"
  }

 implicit val format: Format[InternalAuthEnvironment] = new Format[InternalAuthEnvironment] {
    override def writes(o: InternalAuthEnvironment): JsValue = JsString(o.asString)

    override def reads(json: JsValue): JsResult[InternalAuthEnvironment] = json match {
      case JsString("PROD") => JsSuccess(Prod)
      case JsString("QA") => JsSuccess(Qa)
      case _ => JsError("Invalid Internal Auth Environment")
    }
  }
}
