/*
 * Copyright 2019 HM Revenue & Customs
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

import com.google.inject.Inject
import javax.inject.Singleton
import play.api.Logger
import play.api.libs.json.{JsError, Json, OFormat, Reads}
import play.api.mvc.MessagesControllerComponents
import uk.gov.hmrc.play.bootstrap.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{ApiSlugInfoFormats, DependencyConfig, SlugInfo}
import uk.gov.hmrc.serviceconfigs.service.SlugConfigurationService

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ArtefactController @Inject()
                                (mcc: MessagesControllerComponents,
                                 slugInfoService: SlugConfigurationService)
                                (implicit executionContext: ExecutionContext)
  extends BackendController(mcc) {

  def setSlugConfigurationInfo() = Action.async(parse.json) { implicit request =>
    Logger.debug("Starting 'setSlugConfigurationInfo'")
    implicit val slugInfoFormat: OFormat[SlugInfo] = ApiSlugInfoFormats.siFormat
    withJsonBody[SlugInfo] { slugInfo =>
      Logger.debug(s"Slug Info to persist: ${slugInfo.name} ${slugInfo.version}")
      slugInfoService.addSlugInfo(slugInfo)
      Future(Ok(""))
    }

  }

  def setDependencyConfiguration() = Action.async(parse.json) { implicit request =>
    Logger.debug("Starting 'setDependencyConfiguration'")
    implicit val dcFormat: OFormat[DependencyConfig] = ApiSlugInfoFormats.dcFormat
    withJsonBody[Seq[DependencyConfig]] { dependencyConfigs =>
      Logger.debug(s"Dependency Configs to persist.")
      slugInfoService.addDependencyConfigurations(dependencyConfigs)
      Future(Ok(""))
    }
  }
}
