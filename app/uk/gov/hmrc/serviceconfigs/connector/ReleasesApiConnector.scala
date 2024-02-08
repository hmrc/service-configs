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

package uk.gov.hmrc.serviceconfigs.connector

import javax.inject.{Inject, Singleton}
import play.api.libs.functional.syntax._
import play.api.libs.json._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig
import uk.gov.hmrc.serviceconfigs.model.{CommitId, Environment, FileName, RepoName, ServiceName, Version}
import uk.gov.hmrc.http.HttpReads.Implicits._
import uk.gov.hmrc.http.StringContextOps
import java.net.URL

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ReleasesApiConnector @Inject()(
  httpClientV2 : HttpClientV2,
  serviceConfig: ServicesConfig
)(implicit ec: ExecutionContext) {
  import ReleasesApiConnector._

  private val serviceUrl: URL = url"${serviceConfig.baseUrl("releases-api")}"

  implicit val sdir: Reads[ServiceDeploymentInformation] = ServiceDeploymentInformation.reads

  def getWhatsRunningWhere()(implicit hc: HeaderCarrier): Future[Seq[ServiceDeploymentInformation]] =
    httpClientV2
      .get(url"$serviceUrl/releases-api/whats-running-where")
      .execute[Seq[ServiceDeploymentInformation]]
}

object ReleasesApiConnector {
  val environmentReads: Reads[Option[Environment]] =
      JsPath.read[String].map(Environment.parse)

  case class DeploymentConfigFile(
    repoName: RepoName,
    fileName: FileName,
    commitId: CommitId
  )

  object DeploymentConfigFile {
    val reads: Reads[DeploymentConfigFile] =
      ( (__ \ "repoName").read[String].map(RepoName.apply)
      ~ (__ \ "fileName").read[String].map(FileName.apply)
      ~ (__ \ "commitId").read[String].map(CommitId.apply)
      )(DeploymentConfigFile.apply _)
  }

  case class Deployment(
    serviceName   : ServiceName
  , optEnvironment: Option[Environment]
  , version       : Version
  , deploymentId  : Option[String]
  , config        : Seq[DeploymentConfigFile]
  ) {
    lazy val configId =
      config.foldLeft(serviceName.asString + "_" + version.original)((acc, c) =>
        acc + "_" + c.repoName.asString + "_" + c.commitId.asString.take(7)
      )
  }

  case class ServiceDeploymentInformation(
    serviceName: ServiceName
  , deployments: Seq[Deployment]
  )

  object ServiceDeploymentInformation {
    private def deploymentReads(serviceName: ServiceName): Reads[Deployment] = {
      implicit val er  = environmentReads
      implicit val vf  = Version.format
      implicit val dcf = DeploymentConfigFile.reads
      ( Reads.pure(serviceName)
      ~ (__ \ "environment"  ).read[Option[Environment]]
      ~ (__ \ "versionNumber").read[Version]
      ~ (__ \ "deploymentId" ).readNullable[String]
      ~ (__ \ "config"       ).read[Seq[DeploymentConfigFile]]
      )(Deployment.apply _)
    }

    val reads: Reads[ServiceDeploymentInformation] = {
      implicit val snf = ServiceName.format
      ( (__ \ "applicationName").read[ServiceName]
      ~ (__ \ "applicationName").read[ServiceName].flatMap { serviceName =>
          implicit val dr = deploymentReads(serviceName)
          (__ \ "versions"       ).read[Seq[Deployment]]
        }
      )(ServiceDeploymentInformation.apply _)
    }
  }
}
