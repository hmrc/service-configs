/*
 * Copyright 2019 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.parser

import scala.io.Source

/**
  * NginxConfigIndexer takes nginx config files and returns a map containing
  * the line number of each path in the file.
  */
object NginxConfigIndexer {

  private val locationRegex = """location\s+[\^\~\~*\^~=]*\s*(.+)\s*\{""".r

  def index(source: String) : Map[String, Int] = {
    Source.fromString(source)
      .getLines()
      .zipWithIndex
      .filter(_._1.contains("location"))
      .map(z => locationRegex.findFirstMatchIn(z._1).map(_.group(1).trim).getOrElse(z._1) -> (1 + z._2))
      .toMap

  }

  private val baseURL = "https://github.com/hmrc/mdtp-frontend-routes/blob"
  private val filename = "frontend-proxy-application-rules.conf"

  def generateUrl(branch: String, env: String, frontendPath: String, indexes: Map[String, Int]) : Option[String] = {
    indexes.get(frontendPath).map( idx => s"$baseURL/$branch/$env/$filename#L$idx")
  }



}
