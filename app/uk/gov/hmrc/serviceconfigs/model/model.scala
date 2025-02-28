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
import play.api.libs.json.{Json, JsPath, Format, OFormat, OWrites, Reads}

case class ArtefactName  (asString: String) extends AnyVal
case class CommitId      (asString: String) extends AnyVal
case class RepoName      (asString: String) extends AnyVal
case class FileName      (asString: String) extends AnyVal
case class Content       (asString: String) extends AnyVal
case class ServiceName   (asString: String) extends AnyVal
case class Tag           (asString: String) extends AnyVal
case class TeamName      (asString: String) extends AnyVal
case class DigitalService(asString: String) extends AnyVal

object ArtefactName:
  val format =
    summon[Format[String]].inmap(ArtefactName.apply, _.asString)

object CommitId:
  val format =
    summon[Format[String]].inmap(CommitId.apply, _.asString)

object RepoName:
  val format =
    summon[Format[String]].inmap(RepoName.apply, _.asString)

object FileName:
  val format =
    summon[Format[String]].inmap(FileName.apply, _.asString)

object Content:
  val format =
    summon[Format[String]].inmap(Content.apply, _.asString)

object ServiceName:
  val format =
    summon[Format[String]].inmap(ServiceName.apply, _.asString)

object JsonUtil:
  def ignoreOnWrite[A : Reads](path: JsPath) =
    OFormat[A](Reads.at[A](path), OWrites[A](_ => Json.obj()))
