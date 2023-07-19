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

package uk.gov.hmrc.serviceconfigs.parser

import play.api.Logging
import play.api.libs.json.Reads
import uk.gov.hmrc.serviceconfigs.model.InternalAuthEnvironment._
import uk.gov.hmrc.serviceconfigs.model.{InternalAuthConfig, InternalAuthEnvironment, ServiceName, Services}
import uk.gov.hmrc.serviceconfigs.util.YamlUtil.fromYaml

import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry, ZipInputStream}
import scala.util.matching.Regex

class InternalAuthConfigParser extends Logging {

  private def readEntry(zipInputStream: ZipInputStream, entry: ZipEntry, env: InternalAuthEnvironment, path: String): Set[InternalAuthConfig] = {
    val outputStream = new ByteArrayOutputStream
    val buffer = new Array[Byte](4096)
    var bytesRead = zipInputStream.read(buffer)
    while (bytesRead != -1) {
      outputStream.write(buffer, 0, bytesRead)
      bytesRead = zipInputStream.read(buffer)
    }
    val output = new String(outputStream.toByteArray, "UTF-8")
    parseGrants(output, env)
  }

  def parseGrants(parsedYaml: String, env: InternalAuthEnvironment):Set[InternalAuthConfig] = {
    implicit val reads: Reads[Option[Services]] = Services.serviceReads

    fromYaml[List[Option[Services]]](parsedYaml).collect {
      case Some(services) => services.serviceName.map(s => InternalAuthConfig(ServiceName(s), env, services.grant))
    }.flatten.toSet

  }

  def parseZip(z: ZipInputStream): Set[InternalAuthConfig] = {
    val prodConfigRegex: Regex = """prod/([^index].*).yaml""".r
    val qaConfigRegex: Regex = """qa/([^index].*).yaml""".r

    Iterator
      .continually(z.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(Set.empty[InternalAuthConfig]) { (acc, entry) =>
        val path = entry.getName.drop(entry.getName.indexOf('/') + 1)
        path match {
          case prodConfigRegex(_) => acc ++ readEntry(z, entry, Prod, path)
          case qaConfigRegex(_) => acc ++ readEntry(z, entry, Qa, path)
          case _ => acc
        }
      }
  }
}