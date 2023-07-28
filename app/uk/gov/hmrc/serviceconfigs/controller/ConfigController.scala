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

import io.swagger.annotations.{Api, ApiOperation, ApiParam}
import play.api.Configuration
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.connector.ReleasesApiConnector
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, ServiceType, SlugInfoFlag, Tag, TeamName, FilterType}
import uk.gov.hmrc.serviceconfigs.parser.ConfigValue
import uk.gov.hmrc.serviceconfigs.service.{ConfigService, ConfigWarning, ConfigWarningService}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigEnvironment, ConfigSourceValue, ConfigSourceValueWithWarnings, KeyName, RenderedConfigSourceValue}
import uk.gov.hmrc.serviceconfigs.persistence.AppliedConfigRepository

import javax.inject.{Inject, Singleton}
import scala.concurrent.{Future, ExecutionContext}

@Singleton
@Api("Github Config")
class ConfigController @Inject()(
  configuration       : Configuration,
  configService       : ConfigService,
  configWarningService: ConfigWarningService,
  cc                  : ControllerComponents,
  releasesApiConnector: ReleasesApiConnector
)(implicit
  ec: ExecutionContext
) extends BackendController(cc) {
  import ConfigController._

  @ApiOperation(
    value = "Retrieves all of the config for a given service, broken down by environment",
    notes = """Searches all config sources for all environments and pulls out the the value of each config key"""
  )
  def serviceConfig(
    @ApiParam(value = "The service name to query") serviceName: ServiceName,
    @ApiParam(value = "Latest or As Deployed")     latest     : Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    configService.configByEnvironment(serviceName, latest).map { e =>
      Ok(Json.toJson(e))
    }
  }

  // @ApiOperation(
  //   value = "Retrieves all of the config for a given service, broken down by config key",
  //   notes = """Searches all config sources for all environments and pulls out the the value of each config key"""
  // )
  // def configByKey(
  //   @ApiParam(value = "The service name to query") serviceName: ServiceName,
  //   @ApiParam(value = "Latest or As Deployed")     latest     : Boolean,
  //   showWarnings: Boolean
  // ): Action[AnyContent] = Action.async { implicit request =>
  //   configService.configByKey(serviceName, latest).map { k =>
  //     Ok(Json.toJson(k))
  //   }
  // }

  import cats.implicits._
  def configByKey(
    serviceName : ServiceName,
    latest      : Boolean,
    showWarnings: Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    ConfigService.ConfigEnvironment
      .values
      .toList
      .foldLeftM[Future, Map[KeyName, Map[ConfigEnvironment, Seq[ConfigSourceValueWithWarnings]]]](Map.empty) {
        case (acc, environment) =>
          for {
            configSourceEntries <- configService.configSourceEntries(environment, serviceName, latest)
            resultingConfig     =  configService.resultingConfig(configSourceEntries)
            allWarnings         <- environment.slugInfoFlag match {
                                     case SlugInfoFlag.ForEnvironment(env)
                                       if showWarnings => configWarningService.warnings(env, serviceName, latest = latest, configSourceEntries, resultingConfig)
                                     case _            => Future.successful(Nil)
                                   }
            res                 =  configSourceEntries.foldLeft(acc) {
                                     case (acc2, cse) =>
                                       acc2 ++ cse.entries.map {
                                         case (key, value)  =>
                                           val envMap   = acc2.getOrElse(key, Map.empty)
                                           val values   = envMap.getOrElse(environment, Seq.empty)
                                           val warnings = allWarnings.collect { case x if x.key == key => x.warning }
                                           key -> (envMap + (environment -> (values :+ ConfigSourceValueWithWarnings(cse.source, cse.sourceUrl, value, warnings))))
                                       }
                                   }
          } yield res
      }.map(xs => Ok(Json.toJson(scala.collection.immutable.ListMap(xs.toSeq.sortBy(_._1): _*)))) // sort by keys
  }

  private def maxSearchLimit = configuration.get[Int]("config-search.max-limit")
  @ApiOperation(
    value = "Search for config using the list query params below.",
    notes = "Queries are not allowed to be over the configured max search limit"
  )
  def search(
    @ApiParam(value = "The key to query"     ) key            : Option[String],
    @ApiParam(value = "The key filter type"  ) keyFilterType  : FilterType,
    @ApiParam(value = "The value to query."  ) value          : Option[String],
    @ApiParam(value = "The value filter type") valueFilterType: FilterType,
    @ApiParam(value = "Environment filter"   ) environment    : Seq[Environment],
    @ApiParam(value = "Team name filter"     ) teamName       : Option[TeamName],
    @ApiParam(value = "serviceType filter"   ) serviceType    : Option[ServiceType],
    @ApiParam(value = "Tag filter"           ) tag            : Seq[Tag],
  ): Action[AnyContent] = Action.async {
    implicit val acf = AppliedConfigRepository.AppliedConfig.format
    configService
      .search(key, keyFilterType, value, valueFilterType, environment, teamName, serviceType, tag)
      .map {
        case k if (k.size > maxSearchLimit) => Forbidden(s"Queries returning over $maxSearchLimit results are not allowed")
        case k                              => Ok(Json.toJson(k))
      }
  }

  @ApiOperation(
    value = "Retrieves all config keys, unless filtered."
  )
  def configKeys(
    @ApiParam(value = "Team name filter") teamName: Option[TeamName]
  ): Action[AnyContent] = Action.async {
    configService
      .findConfigKeys(teamName)
      .map(res => Ok(Json.toJson(res)))
  }

  def warnings(
    serviceName: ServiceName,
    environment: Environment,
    latest     : Boolean
  ): Action[AnyContent] =
    Action.async { implicit request =>
      for {
        configSourceEntries <- configService.configSourceEntries(ConfigService.ConfigEnvironment.ForEnvironment(environment), serviceName, latest)
        resultingConfig     =  configService.resultingConfig(configSourceEntries)
        warnings            <- configWarningService.warnings(environment, serviceName, latest = latest, configSourceEntries, resultingConfig)
      } yield Ok(Json.toJson(warnings))
    }
}

object ConfigController {
  implicit val csew: Writes[ConfigService.ConfigSourceEntries] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "entries"  ).write[Map[KeyName, String]]
                        .contramap[Map[KeyName, ConfigValue]](_.view.mapValues(_.asString).toMap)
    )(unlift(ConfigService.ConfigSourceEntries.unapply))

  // implicit val csvw: Writes[ConfigSourceValue] =
  //   ( (__ \ "source"   ).write[String]
  //   ~ (__ \ "sourceUrl").writeNullable[String]
  //   ~ (__ \ "value"    ).write[String]
  //                       .contramap[ConfigValue](_.asString)
  //   )(unlift(ConfigSourceValue.unapply))

  implicit val csvww: Writes[ConfigSourceValueWithWarnings] =
    ( (__ \ "source"   ).write[String]
    ~ (__ \ "sourceUrl").writeNullable[String]
    ~ (__ \ "value"    ).write[String]
                        .contramap[ConfigValue](_.asString)
    ~ (__ \ "warnings" ).write[Seq[String]]
    )(unlift(ConfigSourceValueWithWarnings.unapply))

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

  implicit def envMapWrites[A <: ConfigEnvironment, B: Writes]: Writes[Map[A, B]] =
    implicitly[Writes[Map[String, B]]].contramap(m => m.map { case (e, a) => (e.name, a) })
}
