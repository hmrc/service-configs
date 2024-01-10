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

package uk.gov.hmrc.serviceconfigs.persistence

import org.mongodb.scala.bson.BsonDocument
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigV2, Environment, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class DeploymentConfigRepositoryV2Spec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DeploymentConfigV2] {

  override lazy val repository = new DeploymentConfigRepositoryV2(mongoComponent)

  "DeploymentConfigRepository" should {

    "foo" in {
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")
      val serviceName3 = ServiceName("serviceName3")
      val developmentDeploymentConfigs = Seq(
        mkDeploymentConfig(serviceName1, Environment.Development),
        mkDeploymentConfig(serviceName2, Environment.Development)
      )
      val productionDeploymentConfigs = Seq(
        mkDeploymentConfig(serviceName2, Environment.Production),
        mkDeploymentConfig(serviceName3, Environment.Production)
      )
//      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs.map(toBson)).futureValue
//      repository.replaceEnv(Environment.Production, productionDeploymentConfigs.map(toBson)).futureValue

      repository.getDeploymentConfigs().futureValue shouldBe "dunno"
    }
  }

  def mkDeploymentConfig(serviceName: ServiceName, environment: Environment): DeploymentConfig =
    DeploymentConfig(
      serviceName    = serviceName,
      artifactName   = Some("artefactName"),
      environment    = environment,
      zone           = "public",
      deploymentType = "microservice",
      slots          = 1,
      instances      = 1
    )

  def toBson(deploymentConfig: DeploymentConfig): BsonDocument =
    BsonDocument(
      "name"           -> deploymentConfig.serviceName.asString,
      "artifactName"   -> deploymentConfig.artifactName,
      "environment"    -> deploymentConfig.environment.asString,
      "zone"           -> deploymentConfig.zone,
      "type"           -> deploymentConfig.deploymentType,
      "slots"          -> deploymentConfig.slots.toString,
      "instances"      -> deploymentConfig.instances.toString
    )
}
