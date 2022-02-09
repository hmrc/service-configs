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

import play.api.libs.json.{__, Format, JsError, JsString, JsSuccess, JsValue, OFormat}

case class Version(
  major   : Int,
  minor   : Int,
  patch   : Int,
  original: String
) extends Ordered[Version] {

  override def compare(other: Version): Int =
    if (major == other.major)
      if (minor == other.minor)
        if (patch == other.patch)
          other.original.length - original.length // prefer pure semantic version (e.g. 1.0.0 > 1.0.0-SNAPSHOT)
        else
          patch - other.patch
      else
        minor - other.minor
    else
      major - other.major

  override def toString: String = original

  def normalise: Version =
    Version(major, minor, patch, original = s"$major.$minor.$patch")
}

object Version {
  val format: Format[Version] =
    new Format[Version] {
      override def reads(json: JsValue) =
        json match {
          case JsString(s) => Version.parse(s).map(v => JsSuccess(v)).getOrElse(JsError("Could not parse version"))
          case _           => JsError("Not a string")
        }

      override def writes(v: Version) =
        JsString(v.original)
    }

  val mongoVersionRepositoryFormat :OFormat[Version] =
    (__ \ "version" ).format[Version](format)

  def apply(version: String): Version =
    parse(version).getOrElse(sys.error(s"Could not parse version $version"))

  def apply(major: Int, minor: Int, patch: Int): Version =
    Version(major, minor, patch, s"$major.$minor.$patch")

  def parse(s: String): Option[Version] = {
    val regex3 = """(\d+)\.(\d+)\.(\d+)(.*)""".r
    val regex2 = """(\d+)\.(\d+)(.*)""".r
    val regex1 = """(\d+)(.*)""".r
    s match {
      case regex3(maj, min, patch, _) => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), Integer.parseInt(patch), s))
      case regex2(maj, min,  _)       => Some(Version(Integer.parseInt(maj), Integer.parseInt(min), 0                      , s))
      case regex1(patch,  _)          => Some(Version(0                    , 0                    , Integer.parseInt(patch), s))
      case _                          => None
    }
  }
}
