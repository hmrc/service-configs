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
import uk.gov.hmrc.serviceconfigs.model.{ServiceRelationship, ServiceRelationships, SlugInfo}
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

  def getServiceRelationships(service: String): Future[ServiceRelationships] =
    for {
      inbound  <- serviceRelationshipsRepository.getInboundServices(service)
      outbound <- serviceRelationshipsRepository.getOutboundServices(service)
    } yield ServiceRelationships(inbound, outbound)

  def updateServiceRelationships(): Future[Unit] =
    for {
      slugInfos       <- slugInfoRepository.getAllLatestSlugInfos
      (srs, failures) <- slugInfos.toList.foldMapM[Future, (List[ServiceRelationship], List[String])]( slugInfo =>
                           serviceRelationshipsFromSlugInfo(slugInfo)
                             .map { res => (res.toList, List.empty[String]) }
                             .recover { case NonFatal(e) =>
                               logger.warn(s"Error encountered when getting service relationships for ${slugInfo.name}", e)
                               (List.empty[ServiceRelationship], List(slugInfo.name))
                             }
                         )
      _               <- if(srs.nonEmpty)
                           serviceRelationshipsRepository.putAll(srs)
                         else Future.successful(())
      _               =  if(failures.nonEmpty)
                           logger.warn(s"Failed to update service relationships for ${failures.mkString(", ")}")
                         else ()
    } yield ()

  private[service] def serviceRelationshipsFromSlugInfo(slugInfo: SlugInfo): Future[Seq[ServiceRelationship]] =
    if(slugInfo.applicationConfig.isEmpty) Future.successful(Seq.empty[ServiceRelationship])
    else {
      implicit val hc: HeaderCarrier = HeaderCarrier()

      val config: Future[Seq[ConfigService.ConfigSourceEntries]] = configService.appConfig(slugInfo)

      config.map(
        _.flatMap(_.entries)
          .filter { case (k, _) => k.startsWith("microservice.services") }
          .groupBy { case (k, _) => k.split('.').lift(2).getOrElse("") }
          .toSeq
          .flatMap { case (downstreamService, innerConfig) =>
            val keys = innerConfig.map(_._1)
            if (keys.exists(_.endsWith("host")) && keys.exists(_.endsWith("port")))
              Seq(ServiceRelationship(slugInfo.name, downstreamService))
            else
              Seq.empty[ServiceRelationship]
          }
      )
    }
}
