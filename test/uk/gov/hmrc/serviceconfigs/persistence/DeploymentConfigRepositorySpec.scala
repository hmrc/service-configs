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
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment}

import scala.concurrent.ExecutionContext.Implicits.global

class DeploymentConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DeploymentConfig] {

  override lazy val repository = new DeploymentConfigRepository(mongoComponent)

  "DeploymentConfigRepository" should {
    "replaceEnv correctly" in {
      val developmentDeploymentConfigs = Seq(
        mkDeploymentConfig("service1", Environment.Development),
        mkDeploymentConfig("service2", Environment.Development)
      )
      val productionDeploymentConfigs = Seq(
        mkDeploymentConfig("service2", Environment.Production),
        mkDeploymentConfig("service3", Environment.Production)
      )
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs.map(toBson)).futureValue
      repository.replaceEnv(Environment.Production , productionDeploymentConfigs .map(toBson)).futureValue
      repository.findAll().futureValue shouldBe (developmentDeploymentConfigs ++ productionDeploymentConfigs)

      repository.findAllForEnv(Environment.Development).futureValue shouldBe developmentDeploymentConfigs
      repository.findAllForEnv(Environment.Production ).futureValue shouldBe productionDeploymentConfigs

      val developmentDeploymentConfigs2 = Seq(
        mkDeploymentConfig("service3", Environment.Development),
        mkDeploymentConfig("service4", Environment.Development)
      )

      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs2.map(toBson)).futureValue

      repository.findAllForEnv(Environment.Development).futureValue shouldBe developmentDeploymentConfigs2
      repository.findAllForEnv(Environment.Production ).futureValue shouldBe productionDeploymentConfigs
    }

    "find one by matching service name" in {
      val developmentDeploymentConfigs = Seq(
        mkDeploymentConfig("service1", Environment.Development),
        mkDeploymentConfig("service2", Environment.Development)
      )
      val productionDeploymentConfigs = Seq(
        mkDeploymentConfig("service2", Environment.Production),
        mkDeploymentConfig("service3", Environment.Production)
      )
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs.map(toBson)).futureValue
      repository.replaceEnv(Environment.Production , productionDeploymentConfigs .map(toBson)).futureValue

      repository.findByName(Environment.Development, "service1").futureValue shouldBe developmentDeploymentConfigs.find(_.name == "service1")
      repository.findByName(Environment.Production , "service1").futureValue shouldBe productionDeploymentConfigs .find(_.name == "service1")
    }
  }

  def mkDeploymentConfig(name: String, environment: Environment): DeploymentConfig =
    DeploymentConfig(
      name           = name,
      artifactName   = Some("artefactName"),
      environment    = environment,
      zone           = "public",
      deploymentType = "microservice",
      slots          = 1,
      instances      = 1
    )

  def toBson(deploymentConfig: DeploymentConfig): BsonDocument =
    BsonDocument(
      "name"           -> deploymentConfig.name,
      "artifactName"   -> deploymentConfig.artifactName,
      "environment"    -> deploymentConfig.environment.asString,
      "zone"           -> deploymentConfig.zone,
      "type"           -> deploymentConfig.deploymentType,
      "slots"          -> deploymentConfig.slots.toString,
      "instances"      -> deploymentConfig.instances.toString
    )
}
