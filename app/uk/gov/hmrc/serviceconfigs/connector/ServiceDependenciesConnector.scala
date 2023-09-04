package uk.gov.hmrc.serviceconfigs.connector

import akka.stream.Materializer
import play.api.Logging
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{Reads, __}
import uk.gov.hmrc.http.{HeaderCarrier, StringContextOps}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URLEncoder
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class ServiceDependenciesConnector @Inject() (
  httpClientV2  : HttpClientV2,
  servicesConfig: ServicesConfig)
(implicit
  ec : ExecutionContext,
  mat: Materializer
) extends Logging {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  private val serviceUrl = servicesConfig.baseUrl("service-dependencies")

  def getDependentTeams(group: String, artefact: String, versionRange: String): Future[Seq[String]] =
    httpClientV2.get(url"$serviceUrl/api/serviceDeps?group=$group&artefact=$artefact&versionRang=${URLEncoder.encode(versionRange,"UTF8")}&scope=compile")
      .execute[Seq[ServiceDependencies]] //todo make this a Seq of String if that is all we need
      .map(_.flatMap(_.teams))

}

case class ServiceDependencies(slugName: String, teams: List[String])

object ServiceDependencies {

  implicit val reads: Reads[ServiceDependencies] = {
    ((__ \ "slugName" ).read[String]
      ~ (__ \ "teams").read[List[String]]
   )(ServiceDependencies.apply _)
  }

}
