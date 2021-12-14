/*
 * Copyright 2021 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.testonly

import cats.implicits._

import javax.inject.Inject
import play.api.libs.json.{JsError, Json, Reads}
import play.api.mvc.{Action, AnyContent, BodyParser, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{ApiSlugInfoFormats, DependencyConfig, DeploymentConfig, Environment, SlugInfo, SlugInfoFlag}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.serviceconfigs.persistence.{DependencyConfigRepository, DeploymentConfigRepository, FrontendRouteRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

class IntegrationTestController @Inject()(
  routeRepo            : FrontendRouteRepository,
  dependencyConfigRepo : DependencyConfigRepository,
  slugInfoRepo         : SlugInfoRepository,
  deploymentConfigRepo : DeploymentConfigRepository,
  mcc                  : MessagesControllerComponents
)(implicit ec: ExecutionContext
) extends BackendController(mcc) {

  import MongoFrontendRoute.formats

  implicit val dependencyConfigReads: Reads[DependencyConfig] =
    Json.using[Json.WithDefaultValues].reads[DependencyConfig]

  implicit val siwfr: Reads[SlugInfoWithFlags] = SlugInfoWithFlags.reads

  implicit val deploymentConfigReads: Reads[DeploymentConfig] =
    DeploymentConfig.apiReads

  def validateJson[A: Reads]: BodyParser[A] =
    parse.json.validate(
      _.validate[A].asEither.left.map(e => BadRequest(JsError.toJson(e)))
    )

  def addRoutes(): Action[List[MongoFrontendRoute]] =
    Action.async(validateJson[List[MongoFrontendRoute]]) {
      implicit request =>
        request.body
          .traverse(routeRepo.update)
          .map(_ => Ok("Ok"))
    }

  def clearRoutes(): Action[AnyContent] =
    Action.async {
      routeRepo.clearAll()
        .map(_ => Ok("done"))
    }

  def addSlugDependencyConfigs(): Action[List[DependencyConfig]] =
    Action.async(validateJson[List[DependencyConfig]]) { implicit request =>
      request.body
        .traverse(dependencyConfigRepo.add)
        .map(_ => Ok("Done"))
    }

  def deleteSlugDependencyConfigs(): Action[AnyContent] =
    Action.async {
      dependencyConfigRepo.clearAllData.map(_ => Ok("Done"))
    }

  def addSlugs(): Action[List[SlugInfoWithFlags]] =
    Action.async(validateJson[List[SlugInfoWithFlags]]) { implicit request =>
      request.body.traverse { slugInfoWithFlag =>
        def updateFlag(slugInfoWithFlag: SlugInfoWithFlags, flag: SlugInfoFlag, toSet: SlugInfoWithFlags => Boolean): Future[Unit] =
          if (toSet(slugInfoWithFlag))
            slugInfoRepo.setFlag(flag, slugInfoWithFlag.slugInfo.name, slugInfoWithFlag.slugInfo.version)
          else
            Future(())
        for {
          _ <- slugInfoRepo.add(slugInfoWithFlag.slugInfo)
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.Latest                                  , _.latest      )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ForEnvironment(Environment.Production  ), _.production  )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ForEnvironment(Environment.QA          ), _.qa          )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ForEnvironment(Environment.Staging     ), _.staging     )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ForEnvironment(Environment.Development ), _.development )
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ForEnvironment(Environment.ExternalTest), _.externalTest)
          _ <- updateFlag(slugInfoWithFlag, SlugInfoFlag.ForEnvironment(Environment.Integration ), _.integration )
        } yield ()
      }.map(_ => Ok("Done"))
    }

  def deleteSlugs(): Action[AnyContent] =
    Action.async {
      slugInfoRepo.clearAll().map(_ => Ok("Done"))
    }

  def addDeploymentConfigs(): Action[List[DeploymentConfig]] =
    Action.async(validateJson[List[DeploymentConfig]]) { implicit request =>
      request.body
        .traverse(deploymentConfigRepo.add)
        .map(_ => Ok("Done"))
    }

  def deleteDeploymentConfigs(): Action[AnyContent] =
    Action.async {
      deploymentConfigRepo.deleteAll().map(_ => Ok("Done"))
    }

  case class SlugInfoWithFlags(
    slugInfo    : SlugInfo,
    latest      : Boolean,
    production  : Boolean,
    qa          : Boolean,
    staging     : Boolean,
    development : Boolean,
    externalTest: Boolean,
    integration : Boolean
  )

  object SlugInfoWithFlags {
    import play.api.libs.functional.syntax._
    import play.api.libs.json.__

    val reads: Reads[SlugInfoWithFlags] = {
        implicit val sif = ApiSlugInfoFormats.siFormat
        ( (__                 ).read[SlugInfo]
        ~ (__ \ "latest"      ).read[Boolean]
        ~ (__ \ "production"  ).read[Boolean]
        ~ (__ \ "qa"          ).read[Boolean]
        ~ (__ \ "staging"     ).read[Boolean]
        ~ (__ \ "development" ).read[Boolean]
        ~ (__ \ "externalTest").read[Boolean]
        ~ (__ \ "integration" ).read[Boolean]
        )(SlugInfoWithFlags.apply _)
    }
  }
}
