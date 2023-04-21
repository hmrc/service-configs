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
import uk.gov.hmrc.serviceconfigs.model.Environment
import uk.gov.hmrc.serviceconfigs.persistence.{AppConfigEnvRepository, LastHashRepository}

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source

@Singleton
class AppConfigEnvService @Inject()(
  appConfigEnvRepository: AppConfigEnvRepository,
  lastHashRepository    : LastHashRepository,
  configAsCodeConnector : ConfigAsCodeConnector
)(implicit
  ec : ExecutionContext
) {
  private val logger = Logger(this.getClass)

  def update(): Future[Unit] =
    Environment.values.foldLeftM(())((_, env) => updateEnvironment(env))

  private def updateEnvironment(env: Environment): Future[Unit] =
    (for {
      _            <- EitherT.pure[Future, Unit](logger.info(s"Starting $env"))
      hashKey      =  s"app-config-${env.asString}"
      currentHash  <- EitherT.right[Unit](configAsCodeConnector.getLatestCommitId(s"app-config-${env.asString}").map(_.value))
      previousHash <- EitherT.right[Unit](lastHashRepository.getHash(hashKey))
      oHash        =  Option.when(Some(currentHash) != previousHash)(currentHash)
      hash         <- EitherT.fromOption[Future](oHash, logger.info("No updates"))
      is           <- EitherT.right[Unit](configAsCodeConnector.streamGithub(s"app-config-${env.asString}"))
      config       =  try { extractConfig(is) } finally { is.close() }
      _            <- EitherT.right[Unit](appConfigEnvRepository.putAll(env, config))
      _            <- EitherT.right[Unit](lastHashRepository.update(hashKey, hash))
     } yield ()
    ).merge

  private def extractConfig(zip: ZipInputStream): Map[String, String] =
    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(Map.empty[String, String]){ (acc, entry) =>
        val name = entry.getName.drop(entry.getName.indexOf('/') + 1)
        if (name.endsWith(".yaml")) {
          val content = Source.fromInputStream(zip).mkString
          acc + (name -> content)
        } else acc
      }

  def serviceConfigYaml(env: Environment, service: String): Future[Option[String]] =
    appConfigEnvRepository.find(env, s"$service.yaml")
}
