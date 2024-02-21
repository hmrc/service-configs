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

package uk.gov.hmrc.serviceconfigs.util

import com.google.common.base.Charsets
import com.google.common.io.CharStreams

import java.util.zip.ZipInputStream
import java.io.{FilterInputStream, InputStreamReader}
import scala.io.Source
import scala.util.matching.Regex

object ZipUtil {

  import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
  def findRepos(zip: ZipInputStream, repos: Seq[Repo], regex: Regex, blob: String): Seq[(Repo, String)] = {
    import scala.collection.mutable.ListBuffer
    val repoBuffer: ListBuffer[Repo] = repos.to(ListBuffer)
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(Seq.empty[(Repo, String)]){ (acc, entry) =>
        val path = entry.getName.drop(entry.getName.indexOf('/') + 1)
        path match {
          case regex(_) => acc ++ CharStreams
                                    .toString(new InputStreamReader(zip, Charsets.UTF_8))
                                    .linesIterator
                                    .zipWithIndex
                                    .flatMap { case (line, idx) =>
                                      repoBuffer
                                        .find(r => line.contains(s"\"${r.name}\"") || line.contains(s"\'${r.name}\'"))
                                        .map { r =>
                                          repoBuffer -= r
                                          (r, s"$blob/HEAD/$path#L${idx + 1}")
                                        }
                                    }
                                    .toSeq
          case _        => acc
        }
      }.sortBy(_._1.name)
    }

  class NonClosableInputStream(zip: ZipInputStream) extends FilterInputStream(zip) {
    override def close(): Unit =
      zip.closeEntry()
  }

  import uk.gov.hmrc.serviceconfigs.util.BuildJobPattern.{aemMatch, microserviceMatch, uploadSlugMatch}
  def findServiceToRepoNames(zip: ZipInputStream, pathRegex: Regex): Seq[(String, String)] = {
    def processLines(lines: Iterator[String]): Iterator[(String, String)] =
      lines.foldLeft(None: Option[String], Seq.empty[(String, String)]) {
        case ((optBuilder, acc), line) =>
          (microserviceMatch(line), aemMatch(line), uploadSlugMatch(line)) match {
            case (Some(msBuilder), _, _ )                         => (Some(msBuilder) ,  acc)
            case (_, Some(aemBuilder), _)                         => (Some(aemBuilder),  acc)
            case (_, _, Some(uploadSlug)) if optBuilder.isDefined => (optBuilder      ,  acc :+ (optBuilder.getOrElse(""), uploadSlug))
            case _                                                => (optBuilder      ,  acc)
          }
      }._2.iterator

    Iterator.continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .flatMap { entry =>
        val path = entry.getName.drop(entry.getName.indexOf('/') + 1)
        if (pathRegex.matches(path)) {
          val content = Source.fromInputStream(zip).getLines()
          processLines(content)
        } else {
          Iterator.empty
        }
      }
      .toSeq
      // only want the cases where slug is different to repo name
      .filterNot { case (repo, slug) => repo == slug }
  }
}

object BuildJobPattern {
  def microserviceMatch(line: String): Option[String] =
    Predef.augmentString("""^new MvnMicroserviceJobBuilder\(\s*[^,]+\s*,\s*(['"])([^'"]+)\1[^'"]*$""").r
      .findFirstMatchIn(line).map(_.group(2))

  def aemMatch(line: String): Option[String] =
    Predef.augmentString("""new MvnAemJobBuilder\([^,]+?,\s*['"]?(.*?)['"]?,.*""").r
      .findFirstMatchIn(line).map(_.group(1))

  def uploadSlugMatch(line: String): Option[String] =
    Predef.augmentString(""".andUploadSlug\([^,]+,\s*[^,]+,\s*(['"])(.*?)\1\)""").r
      .findFirstMatchIn(line).map(_.group(2))
}
