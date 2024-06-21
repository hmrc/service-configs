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

import java.time.Instant

import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

enum SlugInfoFlag(val asString: String):
  case Latest                           extends SlugInfoFlag("latest")
  case ForEnvironment(env: Environment) extends SlugInfoFlag(env.asString)

object SlugInfoFlag:
  val values: List[SlugInfoFlag] =
    Latest :: Environment.values.toList.map(ForEnvironment.apply)

  def parse(s: String): Option[SlugInfoFlag] =
    values.find(_.asString.equalsIgnoreCase(s))

case class SlugDependency(
  path       : String,
  version    : String,
  group      : String,
  artifact   : String,
  meta       : String = ""
)

case class SlugInfo(
  uri               : String,
  created           : Instant, //not used
  name              : ServiceName,
  version           : Version,
  classpath         : String,  // not stored in Mongo - used to order dependencies before storing
  dependencies      : List[SlugDependency],
  applicationConfig : String,
  includedAppConfig : Map[String, String],
  loggerConfig      : String,
  slugConfig        : String
)

case class DependencyConfig(
  group   : String,
  artefact: String,
  version : String,
  configs : Map[String, String]
)

trait MongoSlugInfoFormats:
  private given Format[SlugDependency] =
    ( (__ \ "path"    ).format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "group"   ).format[String]
    ~ (__ \ "artifact").format[String]
    ~ (__ \ "meta"    ).formatWithDefault[String]("")
    )(SlugDependency.apply, pt => Tuple.fromProductTyped(pt))

  private def ignore[A]: OWrites[A] =
    OWrites[A](_ => Json.obj())

  val slugInfoFormat: Format[SlugInfo] =
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "created"          ).format[Instant](MongoJavatimeFormats.instantFormat)
    ~ (__ \ "name"             ).format[ServiceName](ServiceName.format)
    ~ (__ \ "version"          ).format[Version](Version.format)
    ~ OFormat(Reads.pure(""), ignore[String])
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").formatNullable[String]
                                .inmap[String](_.getOrElse(""), Option.apply)
    ~ (__ \ "includedAppConfig").formatNullable[Map[String, String]]
                                .inmap[Map[String, String]](_.getOrElse(Map.empty), Option.apply)
    ~ (__ \ "loggerConfig"     ).formatNullable[String]
                                .inmap[String](_.getOrElse(""), Option.apply)
    ~ (__ \ "slugConfig"       ).formatNullable[String]
                                .inmap[String](_.getOrElse(""), Option.apply)
    )(SlugInfo.apply, pt => Tuple.fromProductTyped(pt))

  val dependencyConfigFormat: Format[DependencyConfig] =
    ( (__ \ "group"   ).format[String]
    ~ (__ \ "artefact").format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "configs" ).format[Map[String, String]]
                       .inmap[Map[String, String]](
                         _.map { case (k, v) => (k.replaceAll("_DOT_", "."    ), v) }  // for mongo < 3.6 compatibility - '.' and '$'' not permitted in keys
                       , _.map { case (k, v) => (k.replaceAll("\\."  , "_DOT_"), v) }
                       )
    )(DependencyConfig.apply, pt => Tuple.fromProductTyped(pt))

object MongoSlugInfoFormats extends MongoSlugInfoFormats

trait ApiSlugInfoFormats:
  private given Format[SlugDependency] =
    ( (__ \ "path"    ).format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "group"   ).format[String]
    ~ (__ \ "artifact").format[String]
    ~ (__ \ "meta"    ).formatWithDefault[String]("")
    )(SlugDependency.apply, pt => Tuple.fromProductTyped(pt))

  val slugInfoFormat: Format[SlugInfo] =
    ( (__ \ "uri"              ).format[String]
    ~ (__ \ "created"          ).format[Instant]
    ~ (__ \ "name"             ).format[ServiceName](ServiceName.format)
    ~ (__ \ "version"          ).format[Version](Version.format)
    ~ (__ \ "classpath"        ).format[String]
    ~ (__ \ "dependencies"     ).format[List[SlugDependency]]
    ~ (__ \ "applicationConfig").format[String]
    ~ (__ \ "includedAppConfig").formatNullable[Map[String, String]]
                                .inmap[Map[String, String]](_.getOrElse(Map.empty), Option.apply)
    ~ (__ \ "loggerConfig"     ).format[String]
    ~ (__ \ "slugConfig"       ).format[String]
    )(SlugInfo.apply, pt => Tuple.fromProductTyped(pt))

  val dependencyConfigFormat: Format[DependencyConfig] =
    ( (__ \ "group"   ).format[String]
    ~ (__ \ "artefact").format[String]
    ~ (__ \ "version" ).format[String]
    ~ (__ \ "configs" ).format[Map[String, String]]
    )(DependencyConfig.apply, pt => Tuple.fromProductTyped(pt))

object ApiSlugInfoFormats extends ApiSlugInfoFormats
