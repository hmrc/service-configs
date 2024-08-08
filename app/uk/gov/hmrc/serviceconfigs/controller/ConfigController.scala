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
import uk.gov.hmrc.serviceconfigs.persistence.{AppliedConfigRepository, DeploymentEventRepository, ServiceToRepoNameRepository}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigSourceValue, KeyName, RenderedConfigSourceValue}
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
)(using
  ec: ExecutionContext
) extends BackendController(cc):
  import ConfigController._

  def serviceConfig(
    serviceName: ServiceName,
    environment: Seq[Environment],
    version    : Option[Version],
    latest     : Boolean
  ): Action[AnyContent] =
    Action.async: request =>
      given RequestHeader = request
      given Writes[Map[ConfigService.ConfigEnvironment, Seq[ConfigService.ConfigSourceEntries]]] = mapWrites
      configService
        .configByEnvironment(serviceName, environment, version, latest)
        .map: e =>
          Ok(Json.toJson(e))

  def deploymentEvents(serviceName: ServiceName, range: DeploymentDateRange): Action[AnyContent] =
    Action.async:
      given Writes[DeploymentEventRepository.DeploymentEvent] = DeploymentEventRepository.DeploymentEvent.apiFormat
      configService
        .getDeploymentEvents(serviceName, range)
        .map:
          events => Ok(Json.toJson(events))

  def configChanges(deploymentId: String, fromDeploymentId: Option[String]): Action[AnyContent] =
    Action.async: request =>
      given RequestHeader = request
      given Writes[ConfigService.ConfigChanges] = configChangesWrites
      configService.configChanges(deploymentId, fromDeploymentId)
        .map:
          case Left(msg)      => BadRequest(msg)
          case Right(changes) => Ok(Json.toJson(changes))

  def configChangesNextDeployment(serviceName: ServiceName, environment: Environment, version: Version): Action[AnyContent] =
    Action.async: request =>
      given RequestHeader = request
      given Writes[ConfigService.ConfigChange] = configChangeWrites
      configService.configChangesNextDeployment(serviceName, environment, version)
        .map:
          changes => Ok(Json.toJson(changes))

  private val maxSearchLimit = configuration.get[Int]("config-search.max-limit")
  def search(
    key            : Option[String],
    keyFilterType  : FilterType,
    value          : Option[String],
    valueFilterType: FilterType,
    environment    : Seq[Environment],
    teamName       : Option[TeamName],
    serviceType    : Option[ServiceType],
    tag            : Seq[Tag],
  ): Action[AnyContent] =
    Action.async:
      given Writes[AppliedConfigRepository.AppliedConfig] = AppliedConfigRepository.AppliedConfig.format
      configService
        .search(key, keyFilterType, value, valueFilterType, environment, teamName, serviceType, tag)
        .map:
          case k if (k.size > maxSearchLimit) => Forbidden(s"Queries returning over $maxSearchLimit results are not allowed")
          case k                              => Ok(Json.toJson(k))

  def configKeys(teamName: Option[TeamName]): Action[AnyContent] =
    Action.async:
      configService
        .findConfigKeys(teamName)
        .map: res =>
          Ok(Json.toJson(res))

  def warnings(
    serviceName : ServiceName,
    environments: Seq[Environment],
    version     : Option[Version],
    latest      : Boolean
  ): Action[AnyContent] =
    Action.async: request =>
      given RequestHeader = request
      given Writes[ConfigWarning] = configWarningWrites
        configWarningService
        .warnings(environments, serviceName, version, latest)
        .map: res =>
          Ok(Json.toJson(res))

  def repoNameForService(
    serviceName : Option[ServiceName],
    artefactName: Option[ArtefactName]
  ): Action[AnyContent] =
    Action.async:
      given Format[RepoName] = RepoName.format
      serviceToRepoNameRepository
        .findRepoName(serviceName, artefactName)
        .map:
          _.fold(NotFound(""))(res => Ok(Json.toJson(res)))

object ConfigController:
  val configSourceEntriesWrites: Writes[ConfigService.ConfigSourceEntries] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "entries"  ).write[Map[KeyName, String]]
                        .contramap[Map[KeyName, ConfigValue]](_.view.mapValues(_.asString).toMap)
    )(pt => Tuple.fromProductTyped(pt))

  private val configSourceValueWrites: Writes[ConfigSourceValue] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "value"    ).write[String]
                        .contramap[ConfigValue](_.asString)
    )(pt => Tuple.fromProductTyped(pt))

  private val renderedConfigSourceValueWrites: Writes[RenderedConfigSourceValue] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "value"    ).write[String]
    )(pt => Tuple.fromProductTyped(pt))

  val configWarningWrites: Writes[ConfigWarning] =
    given Writes[Environment] = Environment.format
    given Writes[ServiceName] = ServiceName.format
    given Writes[RenderedConfigSourceValue] = renderedConfigSourceValueWrites
    ( (__ \ "environment").write[Environment]
    ~ (__ \ "serviceName").write[ServiceName]
    ~ (__ \ "key"        ).write[KeyName]
    ~ (__ \ "value"      ).write[RenderedConfigSourceValue]
    ~ (__ \ "warning"    ).write[String]
    )(pt => Tuple.fromProductTyped(pt))

  private def envMapWrites[A <: ConfigService.ConfigEnvironment, B: Writes]: Writes[Map[A, B]] =
    summon[Writes[Map[String, B]]]
      .contramap(_.map { case (e, a) => (e.name, a) })

  val mapWrites: Writes[Map[ConfigService.ConfigEnvironment, Seq[ConfigService.ConfigSourceEntries]]] =
    given Writes[ConfigService.ConfigSourceEntries] = configSourceEntriesWrites
    envMapWrites

  val configChangeWrites: Writes[ConfigService.ConfigChange] =
    given Writes[ConfigSourceValue] = configSourceValueWrites
    ( (__ \ "from").writeNullable[ConfigSourceValue]
    ~ (__ \ "to"  ).writeNullable[ConfigSourceValue]
    )(cc => (cc.from, cc.to))

  val configChangesWrites: Writes[ConfigService.ConfigChanges] =
    given Writes[CommitId] = CommitId.format
    given Writes[Environment] = Environment.format
    given Writes[ConfigService.ConfigChanges.BaseConfigChange] =
      ( (__ \ "from"     ).writeNullable[CommitId]
      ~ (__ \ "to"       ).writeNullable[CommitId]
      ~ (__ \ "githubUrl").write[String]
      )(cc => (cc.from, cc.to, cc.githubUrl))
    given Writes[ConfigService.ConfigChanges.CommonConfigChange] =
      ( (__ \ "from"     ).writeNullable[CommitId]
      ~ (__ \ "to"       ).writeNullable[CommitId]
      ~ (__ \ "githubUrl").write[String]
      )(cc => (cc.from, cc.to, cc.githubUrl))
    given Writes[ConfigService.ConfigChanges.EnvironmentConfigChange] =
      ( (__ \ "environment").write[Environment]
      ~ (__ \ "from"       ).writeNullable[CommitId]
      ~ (__ \ "to"         ).writeNullable[CommitId]
      ~ (__ \ "githubUrl"  ).write[String]
      )(cc => (cc.environment, cc.from, cc.to, cc.githubUrl))
    given Writes[ConfigService.ConfigChange] = configChangeWrites
    ( (__ \ "base"   ).write[ConfigService.ConfigChanges.BaseConfigChange]
    ~ (__ \ "common" ).write[ConfigService.ConfigChanges.CommonConfigChange]
    ~ (__ \ "env"    ).write[ConfigService.ConfigChanges.EnvironmentConfigChange]
    ~ (__ \ "changes").write[Map[String, ConfigService.ConfigChange]]
    )(cc => Tuple.fromProductTyped(cc))
