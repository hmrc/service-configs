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

package uk.gov.hmrc.serviceconfigs.service

import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.persistence.{LastHashRepository, OutagePageRepository}

import cats.data.EitherT
import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import play.api.Logging
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class OutagePageService @Inject()(
  configAsCodeConnector: ConfigAsCodeConnector,
  lastHashRepository   : LastHashRepository,
  outagePageRepository : OutagePageRepository,
)(using ec: ExecutionContext) extends Logging:

  def findByServiceName(serviceName: ServiceName): Future[Option[Seq[Environment]]] =
    outagePageRepository.findByServiceName(serviceName)

  def update(): Future[Unit] =
    (for
      _            <- EitherT.pure[Future, Unit](logger.info(s"Updating Outage Pages"))
      repoName     =  RepoName("outage-pages")
      currentHash  <- EitherT.right[Unit](configAsCodeConnector.getLatestCommitId(repoName).map(_.asString))
      previousHash <- EitherT.right[Unit](lastHashRepository.getHash(repoName.asString))
      oHash        =  Option.when(Some(currentHash) != previousHash)(currentHash)
      hash         <- EitherT.fromOption[Future](oHash, logger.info("No updates on outage-pages repository"))
      is           <- EitherT.right[Unit](configAsCodeConnector.streamGithub(repoName))
      outagePages  =  try { extractOutagePages(is) } finally { is.close() }
      _            <- EitherT.right[Unit](outagePageRepository.putAll(outagePages))
      _            <- EitherT.right[Unit](lastHashRepository.update(repoName.asString, hash))
     yield ()
    ).merge

  private[service] def extractOutagePages(codeZip: ZipInputStream): Seq[OutagePage] =
    Iterator
      .continually(codeZip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(Map.empty[ServiceName, List[Environment]]):
        (acc, entry) =>
          extractOutagePage(entry.getName).fold(acc):
            (serviceName, env) => acc.updatedWith(serviceName):
                                    currentEnvs => Option(currentEnvs.fold(List(env))(_ :+ env))
      .map:
        (serviceName, envs) => OutagePage(serviceName, envs)
      .toSeq

  private def extractOutagePage(path: String): Option[(ServiceName, Environment)] =
    path.split("/") match
      case Array(_, env, serviceName, OutagePage.outagePageName) =>
        Environment.parse(env).map(ServiceName(serviceName) -> _)
      case _ => None
