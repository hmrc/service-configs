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

import cats.implicits._
import io.swagger.annotations.{Api, ApiOperation, ApiParam}
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json._
import play.api.libs.functional.syntax._
import play.api.mvc._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.ConfigJson
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName, ServiceType, Tag, TeamName, FilterType}
import uk.gov.hmrc.serviceconfigs.service.{ConfigService, ConfigWarning, ConfigWarningService}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigSourceValue, KeyName}
import uk.gov.hmrc.serviceconfigs.persistence.AppliedConfigRepository

import scala.concurrent.{ExecutionContext, Future}
import uk.gov.hmrc.serviceconfigs.connector.ReleasesApiConnector

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
) extends BackendController(cc)
     with ConfigJson {

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

  @ApiOperation(
    value = "Retrieves all of the config for a given service, broken down by config key",
    notes = """Searches all config sources for all environments and pulls out the the value of each config key"""
  )
  def configByKey(
    @ApiParam(value = "The service name to query") serviceName: ServiceName,
    @ApiParam(value = "Latest or As Deployed")     latest     : Boolean
  ): Action[AnyContent] = Action.async { implicit request =>
    configService.configByKey(serviceName, latest).map { k =>
      Ok(Json.toJson(k))
    }
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

  private implicit val cww: Writes[ConfigWarning] =
    ( (__ \ "key"    ).write[KeyName]
    ~ (__ \ "value"  ).write[ConfigSourceValue]
    ~ (__ \ "warning").write[String]
    )(unlift(ConfigWarning.unapply))


  def warnings(
    serviceName: ServiceName,
    environment: Environment,
    latest     : Boolean
  ): Action[AnyContent] =
    Action.async { implicit request =>
      //calculateAllWarnings()
      //  .onComplete {
      //    case scala.util.Success(_)  => logger.info("calculateAllWarnings - finished")
      //    case scala.util.Failure(ex) => logger.error(s"calculateAllWarnings - failed: ${ex.getMessage()}", ex)
      //  }

      configWarningService
        .warnings(environment, serviceName, latest = false)
        .map(res => Ok(Json.toJson(res)))
    }

  private val logger = play.api.Logger(getClass)

  import java.nio.charset.StandardCharsets
  import java.nio.file.{Files, StandardOpenOption}
  private val path = new java.io.File("warnings.csv").toPath
  println(s"Writing to $path")
  Files.write(path, "".getBytes(StandardCharsets.UTF_8))

  private def escapeCsv(s: String): String =
    "\"" + s.replaceAll("\"", "\"\"") + "\""

  def calculateAllWarnings()(implicit hc: HeaderCarrier): Future[Unit] =
    releasesApiConnector.getWhatsRunningWhere()
      .map { repos =>
        println(s"Processing ${repos.size} repos")
        repos.zipWithIndex.toList.foldLeftM[Future, Unit](()) { case (acc, (repo, i)) =>
          println(s">>>> repo: ${repo.serviceName.asString} ($i/${repos.size})")
          repo.deployments.flatMap(_.optEnvironment.toList).foldLeftM[Future, Unit](acc) { (acc, env) =>
            configWarningService.warnings(env, repo.serviceName, latest = false)
              .map { ws =>
                val w =
                  ws.map { case ConfigWarning(k, cse, r) =>
                    s"${repo.serviceName.asString},${env.asString},$k,${escapeCsv(cse.value.asString)},${cse.source},$r"
                  }.mkString("\n")
                val warnings = if (w.nonEmpty) w + "\n" else w
                Files.write(path, warnings.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND)
                println(warnings)
              }
              .recover { case ex => logger.error(s"Failed to get warnings for $repo, $env: ${ex.getMessage}", ex) }
          }
        }
      }
}
