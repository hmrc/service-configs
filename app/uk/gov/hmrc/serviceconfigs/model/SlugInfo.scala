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

package uk.gov.hmrc.serviceconfigs.model

import java.time.LocalDateTime

import play.api.libs.json.{Json, OFormat, OWrites, Reads, __}
import play.api.libs.functional.syntax._

sealed trait SlugInfoFlag { def asString: String }
object SlugInfoFlag {
  case object Latest                           extends SlugInfoFlag { override val asString = "latest"     }
  case class  ForEnvironment(env: Environment) extends SlugInfoFlag { override val asString = env.asString }

  val values: List[SlugInfoFlag] =
    Latest :: Environment.values.map(ForEnvironment.apply)

  def parse(s: String): Option[SlugInfoFlag] =
    values.find(_.asString.equalsIgnoreCase(s))
}

case class SlugDependency(
  path       : String,
  version    : String,
  group      : String,
  artifact   : String,
  meta       : String = "")

case class SlugInfo(
  uri               : String,
  created           : LocalDateTime, //not used
  name              : String,
  version           : Version,
  teams             : List[String],  //not used
  runnerVersion     : String,        //not used
  classpath         : String,
  jdkVersion        : String,        //not used
  dependencies      : List[SlugDependency],
  applicationConfig : String,
  slugConfig        : String
  )

case class DependencyConfig(
    group   : String
  , artefact: String
  , version : String
  , configs : Map[String, String]
  )

case class SlugMessage(
    info: SlugInfo,
    configs: Seq[DependencyConfig]
  )

trait MongoSlugInfoFormats {
  implicit val sdFormat: OFormat[SlugDependency] = Json.format[SlugDependency]

  def ignore[A]: OWrites[A] = OWrites[A](_ => Json.obj())

  implicit val siFormat: OFormat[SlugInfo] =
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "created"          ).format[LocalDateTime]
    ~ (__ \ "name"             ).format[String]
    ~ (__ \ "version"          ).format[String].inmap[Version](Version.apply, _.original)
    ~ OFormat(Reads.pure(List.empty[String]), ignore[List[String]])
    ~ OFormat(Reads.pure(""), ignore[String])
    ~ OFormat(Reads.pure(""), ignore[String])
    ~ OFormat(Reads.pure(""), ignore[String])
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").formatNullable[String].inmap[String](_.getOrElse(""), Option.apply)
    ~ (__ \ "slugConfig"       ).formatNullable[String].inmap[String](_.getOrElse(""), Option.apply)
    )(SlugInfo.apply, unlift(SlugInfo.unapply))

  val dcFormat: OFormat[DependencyConfig] =
    ( (__ \ "group"   ).format[String]
    ~ (__ \ "artefact").format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "configs" ).format[Map[String, String]]
                       .inmap[Map[String, String]](
                           _.map { case (k, v) => (k.replaceAll("_DOT_", "."    ), v) }  // for mongo < 3.6 compatibility - '.' and '$'' not permitted in keys
                         , _.map { case (k, v) => (k.replaceAll("\\."  , "_DOT_"), v) }
                         )
    )(DependencyConfig.apply, unlift(DependencyConfig.unapply))
}

object MongoSlugInfoFormats extends MongoSlugInfoFormats


trait ApiSlugInfoFormats {

  def ignore[A]: OWrites[A] = OWrites[A](_ => Json.obj())

  implicit val sdFormat: OFormat[SlugDependency] = Json.format[SlugDependency]

  implicit val siFormat: OFormat[SlugInfo] = {
    implicit val vf = Version.apiFormat
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "created"          ).format[LocalDateTime]
    ~ (__ \ "name"             ).format[String]
    ~ (__ \ "version"          ).format[String].inmap[Version](Version.apply, _.original)
    ~ OFormat(Reads.pure(List.empty[String]), ignore[List[String]])
    ~ OFormat(Reads.pure(""), ignore[String])
    ~ (__ \ "classpath"        ).format[String]
    ~ OFormat(Reads.pure(""), ignore[String])
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").format[String]
    ~ (__ \ "slugConfig"       ).format[String]
    )(SlugInfo.apply, unlift(SlugInfo.unapply))
  }

  val dcFormat: OFormat[DependencyConfig] =
    ( (__ \ "group"   ).format[String]
    ~ (__ \ "artefact").format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "configs" ).format[Map[String, String]]
    )(DependencyConfig.apply, unlift(DependencyConfig.unapply))

  val slugFormat: OFormat[SlugMessage] = {
    implicit val dcf = dcFormat
    Json.format[SlugMessage]
  }
}

object ApiSlugInfoFormats extends ApiSlugInfoFormats
