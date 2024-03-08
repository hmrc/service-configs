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

import cats.implicits._

import play.api.Logging
import uk.gov.hmrc.serviceconfigs.connector.{ConfigAsCodeConnector, TeamsAndRepositoriesConnector}
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, UpscanConfig}
import uk.gov.hmrc.serviceconfigs.persistence.UpscanConfigRepository

import java.util.zip.ZipInputStream
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpscanConfigService @Inject()(
  upscanConfigRepository        : UpscanConfigRepository
, configAsCodeConnector         : ConfigAsCodeConnector
, teamsAndRepositoriesConnector : TeamsAndRepositoriesConnector
)(implicit ec: ExecutionContext) extends Logging {

  def update(): Future[Unit] =
    for {
      _             <- Future.successful(logger.info(s"Updating Upscan Config ..."))
      repoNames     <- ( teamsAndRepositoriesConnector.getRepos(repoType = Some("Service"))
                       , teamsAndRepositoriesConnector.getDeletedRepos(repoType = Some("Service"))
                       ).mapN(_ ++ _)
                        .map(_.map(_.name))
      yamlConfigs   <- configAsCodeConnector.streamUpscanAppConfig().map(getYamlConfigFromZip)
      items         =  yamlConfigs.foldLeft(Seq.empty[UpscanConfig]){ case (acc, (env, yaml)) =>
                          val yamlLines = yaml.linesIterator.zipWithIndex.toList
                          repoNames.flatMap(repoName =>
                            yamlLines
                              .find { case (line, _) => line.contains(s""""$repoName"""") }
                              .map  { case (_, idx ) => UpscanConfig(ServiceName(repoName), s"https://github.com/hmrc/upscan-app-config/blob/main/${env.asString}/verify.yaml#L${idx + 1}", env) }
                          ) ++ acc
                       }
      _             =  logger.info(s"Inserting ${items.size} Upscan Config into mongo")
      count         <- upscanConfigRepository.putAll(items)
      _             =  logger.info(s"Inserted $count Upscan Config into mongo")
    } yield ()

  private[service] def getYamlConfigFromZip(zip: ZipInputStream): Map[Environment, String] = {
    val yamlRegex = """(.*)/verify.yaml""".r

    Iterator
      .continually(zip.getNextEntry)
      .takeWhile(_ != null)
      .foldLeft(Map.empty[Environment, String]){ (acc, entry) =>
        val path = entry.getName.drop(entry.getName.indexOf('/') + 1)
        path match {
          case yamlRegex(environment) =>
            val content: String = scala.io.Source.fromInputStream(zip).mkString
            Environment.parse(environment).fold(acc)(e => acc + (e -> content))
          case _ => acc
        }
      }
  }

}
