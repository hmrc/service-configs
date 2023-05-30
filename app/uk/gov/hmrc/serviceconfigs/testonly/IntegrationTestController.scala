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

package uk.gov.hmrc.serviceconfigs.testonly

import cats.implicits._

import javax.inject.{Inject, Singleton}
import org.mongodb.scala.bson.BsonDocument
import play.api.libs.json.{JsError, Json, JsObject, JsValue, Reads}
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.play.bootstrap.backend.controller.BackendController
import uk.gov.hmrc.serviceconfigs.model.{ApiSlugInfoFormats, BobbyRules, DependencyConfig, DeploymentConfig, DeploymentConfigSnapshot, Environment, SlugInfo, SlugInfoFlag}
import uk.gov.hmrc.serviceconfigs.persistence.model.MongoFrontendRoute
import uk.gov.hmrc.serviceconfigs.persistence.{AppliedConfigRepository, BobbyRulesRepository, DependencyConfigRepository, DeploymentConfigRepository, DeploymentConfigSnapshotRepository, FrontendRouteRepository, SlugInfoRepository}

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IntegrationTestController @Inject()(
  bobbyRulesRepository         : BobbyRulesRepository,
  routeRepo                    : FrontendRouteRepository,
  dependencyConfigRepo         : DependencyConfigRepository,
  slugInfoRepo                 : SlugInfoRepository,
  deploymentConfigRepo         : DeploymentConfigRepository,
  deploymentConfigSnapshotRepo : DeploymentConfigSnapshotRepository,
  appliedConfigRepository      : AppliedConfigRepository,
  mcc                          : MessagesControllerComponents
)(implicit ec: ExecutionContext
) extends BackendController(mcc) {

  def delete(dataType: String): Action[AnyContent] = Action.async {
    (dataType match {
      case "routes"                    => routeRepo
      case "bobbyRules"                => bobbyRulesRepository
      case "slugDependencyConfigs"     => dependencyConfigRepo
      case "sluginfos"                 => slugInfoRepo
      case "deploymentConfigs"         => deploymentConfigRepo
      case "deploymentConfigHistories" => deploymentConfigSnapshotRepo
      case "appliedConfig"             => appliedConfigRepository
    })
    .collection.deleteMany(BsonDocument()).toFuture()
    .map(_ => NoContent)
  }

  def post(dataType: String): Action[JsValue] = Action.async(parse.json) { request =>
    val json = request.body
    (dataType match {
      case "routes"                    => addRoutes(json)
      case "bobbyRules"                => addBobbyRules(json)
      case "slugDependencyConfigs"     => addSlugDependencyConfigs(json)
      case "sluginfos"                 => addSlugs(json)
      case "deploymentConfigs"         => addDeploymentConfigs(json)
      case "deploymentConfigHistories" => addDeploymentConfigHistories(json)
      case "appliedConfig"             => addAppliedConfig(json)
    }).map(_.fold(e => BadRequest(e), _ => NoContent))
  }

  private def validateJson[A: Reads](json: JsValue): Either[JsObject, A] =
    json.validate[A].asEither.left.map(JsError.toJson)

  private def addRoutes(json: JsValue): Future[Either[JsObject, Unit]] = {
    implicit val frf = MongoFrontendRoute.formats
    validateJson[Seq[MongoFrontendRoute]](json)
      .traverse(_.traverse_(routeRepo.update))
  }

  private def addBobbyRules(json: JsValue): Future[Either[JsObject, Unit]] =
    validateJson(json)(BobbyRules.apiFormat)
      .traverse(bobbyRulesRepository.putAll)

  private def addSlugDependencyConfigs(json: JsValue): Future[Either[JsObject, Unit]] = {
    implicit val dcf = Json.using[Json.WithDefaultValues].reads[DependencyConfig]
    validateJson[Seq[DependencyConfig]](json)
      .traverse(_.traverse_(dependencyConfigRepo.add))
  }

  private def addSlugs(json: JsValue): Future[Either[JsObject, Unit]] = {
    implicit val siwfr: Reads[SlugInfoWithFlags] = SlugInfoWithFlags.reads
    validateJson[Seq[SlugInfoWithFlags]](json)
      .traverse(
        _.traverse_ { slugInfoWithFlag =>
          def updateFlag(slugInfoWithFlag: SlugInfoWithFlags, flag: SlugInfoFlag, toSet: SlugInfoWithFlags => Boolean): Future[Unit] =
            if (toSet(slugInfoWithFlag))
              slugInfoRepo.setFlag(flag, slugInfoWithFlag.slugInfo.name, slugInfoWithFlag.slugInfo.version)
            else
              Future.unit

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
        }
      )
  }

  private def addDeploymentConfigs(json: JsValue): Future[Either[JsObject, Unit]] = {
    implicit val deploymentConfigReads: Reads[DeploymentConfig] = DeploymentConfig.apiFormat
    validateJson[Seq[DeploymentConfig]](json)
      .traverse(_.traverse_(deploymentConfigRepo.add))
  }

  private def addDeploymentConfigHistories(json: JsValue): Future[Either[JsObject, Unit]] = {
    implicit val deploymentConfigSnapshotReads: Reads[DeploymentConfigSnapshot] = DeploymentConfigSnapshot.apiFormat
    validateJson[Seq[DeploymentConfigSnapshot]](json)
      .traverse(_.traverse_(deploymentConfigSnapshotRepo.add))
  }

  private def addAppliedConfig(json: JsValue): Future[Either[JsObject, Unit]] = {
    implicit val deploymentConfigSnapshotReads: Reads[AppliedConfigRepository.AppliedConfig] = AppliedConfigRepository.AppliedConfig.format
    validateJson[Seq[AppliedConfigRepository.AppliedConfig]](json)
      .traverse[Future, JsObject, Unit](configEntries => appliedConfigRepository.collection.insertMany(configEntries).toFuture().map(_ => ()))
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
        implicit val sif = ApiSlugInfoFormats.slugInfoFormat
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
