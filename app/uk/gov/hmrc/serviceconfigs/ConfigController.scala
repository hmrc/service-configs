/*
 * Copyright 2018 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs

import javax.inject.{Inject, Singleton}
import play.api.libs.json._
import play.api.mvc._
import uk.gov.hmrc.play.bootstrap.controller.BackendController

import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class ConfigController @Inject()(
  configService: ConfigService,
  mcc: MessagesControllerComponents
) extends BackendController(mcc) with ConfigJson {


  def serviceConfig(serviceName: String) = Action.async { implicit request =>
    configService.configByEnvironment(serviceName).map {e =>
        Ok(Json.toJson(e))
    }
  }

//  def serviceConfig(serviceNAme: String, environment: String) = Action.async { implicit request =>
//    configService.configForEnvironment(serviceName, environment).map { c =>
//      Ok(Json.toJson(c))
//    }
//  }

}