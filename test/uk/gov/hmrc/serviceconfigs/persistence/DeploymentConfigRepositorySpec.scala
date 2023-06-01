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
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class DeploymentConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DeploymentConfig] {

  override lazy val repository = new DeploymentConfigRepository(mongoComponent)

  "DeploymentConfigRepository" should {
    "replaceEnv correctly" in {
      val developmentDeploymentConfigs = Seq(
        mkDeploymentConfig(ServiceName("service1"), Environment.Development),
        mkDeploymentConfig(ServiceName("service2"), Environment.Development)
      )
      val productionDeploymentConfigs = Seq(
        mkDeploymentConfig(ServiceName("service2"), Environment.Production),
        mkDeploymentConfig(ServiceName("service3"), Environment.Production)
      )
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs.map(toBson)).futureValue
      repository.replaceEnv(Environment.Production , productionDeploymentConfigs .map(toBson)).futureValue
      repository.findAll().futureValue shouldBe (developmentDeploymentConfigs ++ productionDeploymentConfigs)

      repository.findAllForEnv(Environment.Development).futureValue shouldBe developmentDeploymentConfigs
      repository.findAllForEnv(Environment.Production ).futureValue shouldBe productionDeploymentConfigs

      val developmentDeploymentConfigs2 = Seq(
        mkDeploymentConfig(ServiceName("service3"), Environment.Development),
        mkDeploymentConfig(ServiceName("service4"), Environment.Development)
      )

      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs2.map(toBson)).futureValue

      repository.findAllForEnv(Environment.Development).futureValue shouldBe developmentDeploymentConfigs2
      repository.findAllForEnv(Environment.Production ).futureValue shouldBe productionDeploymentConfigs
    }

    "find one by matching service name" in {
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
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs.map(toBson)).futureValue
      repository.replaceEnv(Environment.Production , productionDeploymentConfigs .map(toBson)).futureValue

      repository.findByName(Environment.Development, serviceName1).futureValue shouldBe developmentDeploymentConfigs.find(_.serviceName == serviceName1)
      repository.findByName(Environment.Production , serviceName1).futureValue shouldBe productionDeploymentConfigs .find(_.serviceName == serviceName1)
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
