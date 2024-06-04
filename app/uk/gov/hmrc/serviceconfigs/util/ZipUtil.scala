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
import uk.gov.hmrc.serviceconfigs.model.RepoName

import java.io.{FilterInputStream, InputStreamReader}
import java.util.zip.ZipInputStream
import scala.io.Source
import scala.util.matching.Regex

object ZipUtil {

  def findRepos(zip: ZipInputStream, reposNames: Seq[RepoName], regex: Regex, blob: String): Seq[(RepoName, String)] = {
    import scala.collection.mutable.ListBuffer
    val repoBuffer: ListBuffer[RepoName] = reposNames.to(ListBuffer)
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(Seq.empty[(RepoName, String)]){ (acc, entry) =>
        val path = entry.getName.drop(entry.getName.indexOf('/') + 1)
        path match {
          case regex(_) => acc ++ CharStreams
                                    .toString(new InputStreamReader(zip, Charsets.UTF_8))
                                    .linesIterator
                                    .zipWithIndex
                                    .flatMap { case (line, idx) =>
                                      repoBuffer
                                        .find(name => line.contains(s"\"${name.asString}\"") || line.contains(s"\'${name.asString}\'"))
                                        .map { r =>
                                          repoBuffer -= r
                                          (r, s"$blob/HEAD/$path#L${idx + 1}")
                                        }
                                    }
                                    .toSeq
          case _        => acc
        }
      }.sortBy(_._1.asString)
    }

  class NonClosableInputStream(zip: ZipInputStream) extends FilterInputStream(zip) {
    override def close(): Unit =
      zip.closeEntry()
  }

  def extractFromFiles[A](zip: ZipInputStream)(processFile: (String, Iterator[String]) => Iterator[A]): Iterator[A] =
    Iterator.continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .flatMap { entry =>
        val path = entry.getName.drop(entry.getName.indexOf('/') + 1)
        processFile(path, Source.fromInputStream(zip).getLines())
      }
}
