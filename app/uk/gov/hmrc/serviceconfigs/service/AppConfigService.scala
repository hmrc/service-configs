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
import uk.gov.hmrc.serviceconfigs.persistence.{AppConfigRepository, AppConfigCommonRepository, LastHashRepository}

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

@Singleton
class AppConfigService @Inject()(
  appConfigRepository      : AppConfigRepository,
  appConfigCommonRepository: AppConfigCommonRepository,
  lastHashRepository       : LastHashRepository,
  configAsCodeConnector    : ConfigAsCodeConnector,
  config                   : Configuration,
  configConnector          : ConfigConnector
)(implicit
  ec : ExecutionContext
) {
  private val logger = Logger(this.getClass)

  def updateAppConfigBase(): Future[Unit] =
    updateLatest("app-config-base", _.endsWith(".conf"), appConfigRepository.putAllHEAD("app-config-base"))

  def updateAppConfigCommon(): Future[Unit] =
    updateLatest("app-config-common", _.endsWith(".yaml"), appConfigCommonRepository.putAllHEAD)

  def updateAllAppConfigEnv(): Future[Unit] =
    Environment.values.foldLeftM(())((_, env) => updateAppConfigEnv(env))

  def updateAppConfigEnv(env: Environment): Future[Unit] =
    updateLatest(s"app-config-${env.asString}", _.endsWith(".yaml"), appConfigRepository.putAllHEAD(s"app-config-${env.asString}"))

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

  def appConfigBaseConf(environment: Environment, serviceName: String, latest: Boolean): Future[Option[String]] =
    appConfigRepository.find(
      repoName    = "app-config-base",
      environment = Option(environment).filterNot(_ => latest),
      fileName    = s"$serviceName.conf"
    )

  def putAppConfigBase(environment: Environment, serviceName: String, commitId: String, content: String): Future[Unit] =
    appConfigRepository.put(AppConfigRepository.AppConfig(
      repoName    = "app-config-base",
      fileName    = s"$serviceName.conf",
      environment = Some(environment),
      commitId    = commitId,
      content     = content
    ))

  def appConfigEnvYaml(environment: Environment, serviceName: String, latest: Boolean): Future[Option[String]] =
    appConfigRepository.find(
      repoName    = s"app-config-${environment.asString}",
      environment = Option(environment).filterNot(_ => latest),
      fileName    = s"$serviceName.yaml"
    )

  def putAppConfigEnv(environment: Environment, serviceName: String, commitId: String, content: String): Future[Unit] =
    appConfigRepository.put(AppConfigRepository.AppConfig(
      repoName    = s"app-config-${environment.asString}",
      fileName    = s"$serviceName.yaml",
      environment = Some(environment),
      commitId    = commitId,
      content     = content
    ))

  def appConfigCommonYaml(environment: Environment, serviceName: String, serviceType: String, latest: Boolean): Future[Option[String]] = {
    val appConfigCommonFileName = {
      // We do store data for `api-microservice` but it is not yaml - it's a link to the `microservice` file
      val st = serviceType match {
        case "api-microservice" => "microservice"
        case other              => other
      }
      s"${environment.asString}-$st-common.yaml"
    }

    if (latest)
      appConfigCommonRepository.findHEAD(appConfigCommonFileName)
    else
      appConfigCommonRepository.find(
        serviceName = serviceName,
        fileName    = appConfigCommonFileName
      )
  }

  def putAppConfigCommon(serviceName: String, fileName: String, commitId: String, content: String): Future[Unit] =
    appConfigCommonRepository.put(AppConfigCommonRepository.AppConfigCommon(
      serviceName = Some(serviceName),
      fileName    = fileName,
      commitId    = commitId,
      content     = content
    ))
}
