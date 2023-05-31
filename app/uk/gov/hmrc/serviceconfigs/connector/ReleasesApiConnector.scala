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
import uk.gov.hmrc.serviceconfigs.model.{Environment, Version}
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

  implicit val sdir = ServiceDeploymentInformation.reads

  def getWhatIsRunningWhere()(implicit hc: HeaderCarrier): Future[Seq[ServiceDeploymentInformation]] =
    httpClientV2
      .get(url"$serviceUrl/releases-api/whats-running-where")
      .execute[Seq[ServiceDeploymentInformation]]
}

object ReleasesApiConnector {
  val environmentReads: Reads[Option[Environment]] =
      JsPath.read[String].map(Environment.parse)

  case class DeploymentConfigFile(
    repoName: String,
    fileName: String,
    commitId: String
  )

  object DeploymentConfigFile {
    val reads: Reads[DeploymentConfigFile] =
      ( (__ \ "repoName").read[String]
      ~ (__ \ "fileName").read[String]
      ~ (__ \ "commitId").read[String]
      )(DeploymentConfigFile.apply _)
  }

  case class Deployment(
    optEnvironment: Option[Environment]
  , version       : Version
  , deploymentId  : Option[String]
  , config        : Seq[DeploymentConfigFile]
  ) {
    lazy val configId =
      config.sortBy(_.repoName).foldLeft(version.original)((acc, c) => acc + "_" + c.repoName + "_" + c.commitId.take(7))
  }

  object Deployment {
    val reads: Reads[Deployment] = {
      implicit val er = environmentReads
      implicit val vf = Version.format
      implicit val dcf = DeploymentConfigFile.reads
      ( (__ \ "environment"  ).read[Option[Environment]]
      ~ (__ \ "versionNumber").read[Version]
      ~ (__ \ "deploymentId" ).readNullable[String]
      ~ (__ \ "config"       ).read[Seq[DeploymentConfigFile]]
      )(Deployment.apply _)
    }
  }

  case class ServiceDeploymentInformation(
      serviceName: String
    , deployments: Seq[Deployment]
    )

  object ServiceDeploymentInformation {
    val reads: Reads[ServiceDeploymentInformation] = {
      implicit val dr = Deployment.reads
      ( (__ \ "applicationName").read[String]
      ~ (__ \ "versions"       ).read[Seq[Deployment]]
      )(ServiceDeploymentInformation.apply _)
    }
  }
}
