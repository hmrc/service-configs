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
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.model.{Environment, RepoName, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.{DeploymentConfigRepository, LastHashRepository, LatestConfigRepository}

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

@Singleton
class AppConfigService @Inject()(
  latestConfigRepository    : LatestConfigRepository,
  lastHashRepository        : LastHashRepository,
  deploymentConfigRepository: DeploymentConfigRepository,
  configAsCodeConnector     : ConfigAsCodeConnector
)(implicit
  ec : ExecutionContext
) {
  private val logger = Logger(this.getClass)

  def updateAppConfigBase(): Future[Unit] =
    updateLatest(RepoName("app-config-base"), _.endsWith(".conf"))(latestConfigRepository.put("app-config-base"))

  def updateAppConfigCommon(): Future[Unit] =
    updateLatest(RepoName("app-config-common"), _.endsWith(".yaml"))(latestConfigRepository.put("app-config-common"))

  def updateAllAppConfigEnv(): Future[Unit] =
    Environment.values.foldLeftM(())((_, env) => updateAppConfigEnv(env))

  def updateAppConfigEnv(env: Environment): Future[Unit] =
    updateLatest(RepoName(s"app-config-${env.asString}"), _.endsWith(".yaml")){ data =>
      for {
        _ <- latestConfigRepository.put(s"app-config-${env.asString}")(data)
        _ <- data.toSeq.foldLeftM(()){ case (_, (filename, content)) =>
               DeploymentConfigService.toDeploymentConfig(
                 serviceName = ServiceName(filename.stripSuffix(".yaml")),
                 environment = env,
                 applied     = false,
                 fileContent = content
               ).fold(Future.unit)(deploymentConfigRepository.add)
             }
      } yield ()
    }

  private def updateLatest(repoName: RepoName, filter: String => Boolean)(store: Map[String, String] => Future[Unit]): Future[Unit] =
    (for {
      _             <- EitherT.pure[Future, Unit](logger.info("Starting"))
      currentHash   <- EitherT.right[Unit](configAsCodeConnector.getLatestCommitId(repoName).map(_.asString))
      previousHash  <- EitherT.right[Unit](lastHashRepository.getHash(repoName.asString))
      oHash         =  Option.when(Some(currentHash) != previousHash)(currentHash)
      hash          <- EitherT.fromOption[Future](oHash, logger.info("No updates"))
      is            <- EitherT.right[Unit](configAsCodeConnector.streamGithub(repoName))
      config        =  try { extractConfig(is, filter) } finally { is.close() }
      _             <- EitherT.right[Unit](store(config))
      _             <- EitherT.right[Unit](lastHashRepository.update(repoName.asString, hash))
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

  def appConfigBaseConf(serviceName: ServiceName): Future[Option[String]] =
    latestConfigRepository.find(
      repoName    = "app-config-base",
      fileName    = s"${serviceName.asString}.conf"
    )

  def appConfigEnvYaml(environment: Environment, serviceName: ServiceName): Future[Option[String]] =
    latestConfigRepository.find(
      repoName    = s"app-config-${environment.asString}",
      fileName    = s"${serviceName.asString}.yaml"
    )

  def appConfigCommonYaml(environment: Environment, serviceType: String): Future[Option[String]] = {
    // `$env-api-frontend-common.yaml` and `$env-api-microservice-common.yaml` are not actually yaml files, they are links to relevant underlying yaml file.
    val st = serviceType match {
      case "api-frontend"     => "frontend"
      case "api-microservice" => "microservice"
      case other              => other
    }

    latestConfigRepository.find(
      repoName = "app-config-common",
      fileName = s"${environment.asString}-$st-common.yaml"
    )
  }
}
