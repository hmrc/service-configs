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
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.model.{ServiceName, ServiceRelationship, ServiceRelationships, SlugInfo}
import uk.gov.hmrc.serviceconfigs.parser.ConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.{ServiceRelationshipRepository, SlugInfoRepository}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

@Singleton
class ServiceRelationshipService @Inject()(
  configService                 : ConfigService,
  slugInfoRepository            : SlugInfoRepository,
  serviceRelationshipsRepository: ServiceRelationshipRepository
)(implicit
  ec: ExecutionContext
) {

  private val logger: Logger = Logger(getClass)

  def getServiceRelationships(serviceName: ServiceName): Future[ServiceRelationships] =
    for {
      inbound  <- serviceRelationshipsRepository.getInboundServices(serviceName)
      outbound <- serviceRelationshipsRepository.getOutboundServices(serviceName)
    } yield ServiceRelationships(inbound.toSet, outbound.toSet)

  def updateServiceRelationships(): Future[Unit] =
    for {
      slugInfos       <- slugInfoRepository.getAllLatestSlugInfos()
      knownServices   =  slugInfos.map(_.name.asString)
      (srs, failures) <- slugInfos.toList.foldMapM[Future, (List[ServiceRelationship], List[ServiceName])](slugInfo =>
                           serviceRelationshipsFromSlugInfo(slugInfo, knownServices)
                             .map(res => (res.toList, List.empty[ServiceName]))
                             .recover { case NonFatal(e) =>
                               logger.warn(s"Error encountered when getting service relationships for ${slugInfo.name}", e)
                               (List.empty[ServiceRelationship], List(slugInfo.name))
                             }
                         )
      _               <- if (srs.nonEmpty)
                           serviceRelationshipsRepository.putAll(srs)
                         else Future.unit
      _               =  if (failures.nonEmpty)
                           logger.warn(s"Failed to update service relationships for ${failures.mkString(", ")}")
                         else ()
    } yield ()

  private val ServiceNameFromHost = "(.*)(?<!stub|stubs)\\.(?:public|protected)\\.mdtp".r
  private val ServiceNameFromKey  = "(?i)(?:(?:dev|test|prod)\\.)?microservice\\.services\\.(.*)\\.host".r


  private[service] def serviceRelationshipsFromSlugInfo(slugInfo: SlugInfo, knownServices: Seq[String]): Future[Seq[ServiceRelationship]] =
    if (slugInfo.applicationConfig.isEmpty)
      Future.successful(Seq.empty[ServiceRelationship])
    else {
      implicit val hc: HeaderCarrier = HeaderCarrier()

      val appConfig: Future[Seq[ConfigService.ConfigSourceEntries]] = configService.appConfig(slugInfo)

      val appConfBase: Map[String, String] =
        ConfigParser.flattenConfigToDotNotation(
          ConfigParser.parseConfString(
            slugInfo.slugConfig.replace("include \"application.conf\"", "")
          )
        )
        .view.mapValues(_.asString).toMap

      appConfig.map(
        _.flatMap(_.entries)
          .collect { case (k @ ServiceNameFromKey(service), _) => (k, service) }
          .flatMap {
            case (_, serviceFromKey) if knownServices.contains(serviceFromKey) =>
              Seq(ServiceRelationship(slugInfo.name, ServiceName(serviceFromKey)))
            case (key, serviceFromKey) =>
                appConfBase.get(key) match {
                  case Some(ServiceNameFromHost(serviceFromHost)) if knownServices.contains(serviceFromHost) =>
                    Seq(ServiceRelationship(slugInfo.name, ServiceName(serviceFromHost)))
                  case _ =>
                    Seq(ServiceRelationship(slugInfo.name, ServiceName(serviceFromKey)))
                }
          }
      )
    }
}
