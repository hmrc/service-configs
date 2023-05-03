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

import cats.data.EitherT
import cats.implicits._
import play.api.{Configuration, Logger}
import uk.gov.hmrc.serviceconfigs.connector.{ConfigAsCodeConnector, ConfigConnector}
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.persistence.{AppConfigBaseRepository, AppConfigCommonRepository, AppConfigEnvRepository, LastHashRepository}

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

@Singleton
class AppConfigService @Inject()(
  appConfigBaseRepository  : AppConfigBaseRepository,
  appConfigCommonRepository: AppConfigCommonRepository,
  appConfigEnvRepository   : AppConfigEnvRepository,
  lastHashRepository       : LastHashRepository,
  configAsCodeConnector    : ConfigAsCodeConnector,
  config                   : Configuration,
  configConnector          : ConfigConnector
)(implicit
  ec : ExecutionContext
) {
  private val logger = Logger(this.getClass)

  def updateAppConfigBase(): Future[Unit] =
    updateLatest("app-config-base", _.endsWith(".conf"), appConfigBaseRepository.putAllHEAD)

  def updateAppConfigCommon(): Future[Unit] =
    updateLatest("app-config-common", _.endsWith(".yaml"), appConfigCommonRepository.putAllHEAD)

  def updateAllAppConfigEnv(): Future[Unit] =
    Environment.values.foldLeftM(())((_, env) => updateAppConfigEnv(env))

  def updateAppConfigEnv(env: Environment): Future[Unit] =
    updateLatest(s"app-config-${env.asString}", _.endsWith(".yaml"), appConfigEnvRepository.putAllHEAD(env, _))

  private def updateLatest(repoName: String, filter: String => Boolean, store: Map[String, String] => Future[Unit]): Future[Unit] =
    (for {
      _             <- EitherT.pure[Future, Unit](logger.info("Starting"))
      currentHash   <- EitherT.right[Unit](configAsCodeConnector.getLatestCommitId(repoName).map(_.value))
      previousHash  <- EitherT.right[Unit](lastHashRepository.getHash(repoName))
      oHash         =  Option.when(Some(currentHash) != previousHash)(currentHash)
      hash          <- EitherT.fromOption[Future](oHash, logger.info("No updates"))
      is            <- EitherT.right[Unit](configAsCodeConnector.streamGithub(repoName))
      config        =  try { extractConfig(is, filter) } finally { is.close() }
      _             <- EitherT.right[Unit](store(config))
      _             <- EitherT.right[Unit](lastHashRepository.update(repoName, hash))
     } yield ()
    ).merge

  private def extractConfig(zip: ZipInputStream, filter: String => Boolean): Map[String, String] =
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(Map.empty[String, String]){ (acc, entry) =>
        val fileName = entry.getName.drop(entry.getName.indexOf('/') + 1)
        if (filter(fileName)) {
          val content = Source.fromInputStream(zip).mkString
          acc + (fileName -> content)
        } else acc
      }

  def serviceConfigBaseConf(serviceName: String, commitId: String): Future[Option[String]] =
    appConfigBaseRepository.findByFileName(s"$serviceName.conf", commitId)

  def serviceCommonConfigYaml(environment: Environment, serviceName: String, serviceType: String, latest: Boolean): Future[Option[String]] =
    if (latest)
      appConfigCommonRepository.findForLatest(environment, serviceType)
    else
      appConfigCommonRepository.findForDeployed(environment, serviceName): Future[Option[String]]

  def serviceConfigYaml(environment: Environment, serviceName: String, latest: Boolean): Future[Option[String]] =
    appConfigEnvRepository.find(environment, s"$serviceName.yaml", latest)
}
