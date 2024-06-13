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

package uk.gov.hmrc.serviceconfigs.controller

import play.api.Configuration
import play.api.libs.functional.syntax._
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model._
import uk.gov.hmrc.serviceconfigs.parser.ConfigValue
import uk.gov.hmrc.serviceconfigs.persistence.{AppliedConfigRepository, ServiceToRepoNameRepository}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigEnvironment, ConfigSourceValue, KeyName, RenderedConfigSourceValue}
import uk.gov.hmrc.serviceconfigs.service.{ConfigService, ConfigWarning, ConfigWarningService}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class ConfigController @Inject()(
  configuration              : Configuration,
  configService              : ConfigService,
  configWarningService       : ConfigWarningService,
  cc                         : ControllerComponents,
  serviceToRepoNameRepository: ServiceToRepoNameRepository,
)(implicit
  ec: ExecutionContext
) extends BackendController(cc) {
  import ConfigController._

  def serviceConfig(
    serviceName: ServiceName,
    environment: Seq[Environment],
    version    : Option[Version],
    latest     : Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    configService.configByEnvironment(serviceName, environment, version, latest).map { e =>
      Ok(Json.toJson(e))
    }
  }

  def deploymentEvents(serviceName: ServiceName, range: DeploymentDateRange): Action[AnyContent] = Action.async {
        configService.getDeploymentEvents(serviceName, range).map { events =>
          Ok(Json.toJson(events))
    }
  }

  private def maxSearchLimit = configuration.get[Int]("config-search.max-limit")
  def search(
    key            : Option[String],
    keyFilterType  : FilterType,
    value          : Option[String],
    valueFilterType: FilterType,
    environment    : Seq[Environment],
    teamName       : Option[TeamName],
    serviceType    : Option[ServiceType],
    tag            : Seq[Tag],
  ): Action[AnyContent] = Action.async {
    implicit val acf = AppliedConfigRepository.AppliedConfig.format
    configService
      .search(key, keyFilterType, value, valueFilterType, environment, teamName, serviceType, tag)
      .map {
        case k if (k.size > maxSearchLimit) => Forbidden(s"Queries returning over $maxSearchLimit results are not allowed")
        case k                              => Ok(Json.toJson(k))
      }
  }

  def configKeys(teamName: Option[TeamName]): Action[AnyContent] = Action.async {
    configService
      .findConfigKeys(teamName)
      .map(res => Ok(Json.toJson(res)))
  }

  def warnings(
    serviceName : ServiceName,
    environments: Seq[Environment],
    version     : Option[Version],
    latest      : Boolean
  ): Action[AnyContent] =
    Action.async { implicit request =>
      configWarningService
        .warnings(environments, serviceName, version, latest)
        .map(res => Ok(Json.toJson(res)))
    }

  def repoNameForService(
    serviceName : Option[ServiceName],
    artefactName: Option[ArtefactName]
  ): Action[AnyContent] =
    Action.async {
    serviceToRepoNameRepository
      .findRepoName(serviceName, artefactName)
      .map(_.fold(NotFound(""))(
        res => Ok(Json.toJson(res))
      ))
  }
}

object ConfigController {
  implicit val csew: Writes[ConfigService.ConfigSourceEntries] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "entries"  ).write[Map[KeyName, String]]
                        .contramap[Map[KeyName, ConfigValue]](_.view.mapValues(_.asString).toMap)
    )(unlift(ConfigService.ConfigSourceEntries.unapply))

  implicit val csvw: Writes[ConfigSourceValue] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "value"    ).write[String]
                        .contramap[ConfigValue](_.asString)
    )(unlift(ConfigSourceValue.unapply))

  implicit val rcsvw: Writes[RenderedConfigSourceValue] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "value"    ).write[String]
    )(unlift(RenderedConfigSourceValue.unapply))

  private implicit val cww: Writes[ConfigWarning] = {
    implicit val ef  = Environment.format
    implicit val snf = ServiceName.format
    ( (__ \ "environment").write[Environment]
    ~ (__ \ "serviceName").write[ServiceName]
    ~ (__ \ "key"        ).write[KeyName]
    ~ (__ \ "value"      ).write[RenderedConfigSourceValue]
    ~ (__ \ "warning"    ).write[String]
    )(unlift(ConfigWarning.unapply))
  }

  implicit val rnf: Format[RepoName] = RepoName.format

  implicit def envMapWrites[A <: ConfigEnvironment, B: Writes]: Writes[Map[A, B]] =
    implicitly[Writes[Map[String, B]]].contramap(m => m.map { case (e, a) => (e.name, a) })
}
