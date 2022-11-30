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

package uk.gov.hmrc.serviceconfigs.util

import com.google.common.base.Charsets
import com.google.common.io.CharStreams

import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo

import java.util.zip.ZipInputStream
import java.io.InputStreamReader
import scala.util.matching.Regex
import scala.collection.mutable.ListBuffer

object Scrapper {
  def findRepos(zip: ZipInputStream, repos: List[Repo], regex: Regex, blob: String): Seq[(Repo, String)] = {
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
}
