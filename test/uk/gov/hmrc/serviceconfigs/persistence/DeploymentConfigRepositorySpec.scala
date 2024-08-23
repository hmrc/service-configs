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
import uk.gov.hmrc.serviceconfigs.model.{ArtefactName, DeploymentConfig, Environment, ServiceName}

import scala.concurrent.ExecutionContext.Implicits.global

class DeploymentConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DeploymentConfig]:

  override val repository: DeploymentConfigRepository =
    DeploymentConfigRepository(mongoComponent)

  "DeploymentConfigRepository" should:
    val applied = true
    "replaceEnv correctly" in:
      val developmentDeploymentConfigs = Seq(
        mkDeploymentConfig(ServiceName("service1"), Environment.Development, applied),
        mkDeploymentConfig(ServiceName("service2"), Environment.Development, applied)
      )
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs, applied).futureValue

      val productionDeploymentConfigs = Seq(
        mkDeploymentConfig(ServiceName("service2"), Environment.Production, applied),
        mkDeploymentConfig(ServiceName("service3"), Environment.Production, applied)
      )
      repository.replaceEnv(Environment.Production , productionDeploymentConfigs , applied).futureValue

      repository.find(applied).futureValue shouldBe (developmentDeploymentConfigs ++ productionDeploymentConfigs)
      repository.find(applied, Seq(Environment.Development)).futureValue shouldBe developmentDeploymentConfigs
      repository.find(applied, Seq(Environment.Production) ).futureValue shouldBe productionDeploymentConfigs

      val developmentDeploymentConfigs2 = Seq(
        mkDeploymentConfig(ServiceName("service3"), Environment.Development, applied),
        mkDeploymentConfig(ServiceName("service4"), Environment.Development, applied)
      )
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs2, applied).futureValue

      repository.find(applied, Seq(Environment.Development)).futureValue shouldBe developmentDeploymentConfigs2
      repository.find(applied, Seq(Environment.Production )).futureValue shouldBe productionDeploymentConfigs

    "find one by matching service name and environment" in:
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")
      val serviceName3 = ServiceName("serviceName3")

      val developmentDeploymentConfigs = Seq(
        mkDeploymentConfig(serviceName1, Environment.Development, applied),
        mkDeploymentConfig(serviceName2, Environment.Development, applied)
      )
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs, applied).futureValue

      val productionDeploymentConfigs = Seq(
        mkDeploymentConfig(serviceName2, Environment.Production, applied),
        mkDeploymentConfig(serviceName3, Environment.Production, applied)
      )
      repository.replaceEnv(Environment.Production , productionDeploymentConfigs , applied).futureValue

      val productionDeploymentConfigsNotApplied = Seq(
        mkDeploymentConfig(serviceName2, Environment.Production, !applied),
        mkDeploymentConfig(serviceName3, Environment.Production, !applied)
      )
      repository.replaceEnv(Environment.Production , productionDeploymentConfigsNotApplied , !applied).futureValue

      repository.find(applied , Seq(Environment.Development), Seq(serviceName1)).futureValue shouldBe developmentDeploymentConfigs         .filter(_.serviceName == serviceName1)
      repository.find(applied , Seq(Environment.Production ), Seq(serviceName1)).futureValue shouldBe productionDeploymentConfigs          .filter(_.serviceName == serviceName1)
      repository.find(!applied, Seq(Environment.Production ), Seq(serviceName1)).futureValue shouldBe productionDeploymentConfigsNotApplied.filter(_.serviceName == serviceName1)

    "find by matching service names" in:
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")
      val serviceName3 = ServiceName("serviceName3")

      val developmentDeploymentConfigs = Seq(
        mkDeploymentConfig(serviceName1, Environment.Development, applied),
        mkDeploymentConfig(serviceName2, Environment.Development, applied)
      )
      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs, applied).futureValue

      val productionDeploymentConfigs = Seq(
        mkDeploymentConfig(serviceName2, Environment.Production, applied),
        mkDeploymentConfig(serviceName3, Environment.Production, applied)
      )
      repository.replaceEnv(Environment.Production , productionDeploymentConfigs , applied).futureValue

      repository.find(applied, serviceNames = Seq(serviceName2)).futureValue shouldBe (developmentDeploymentConfigs ++ productionDeploymentConfigs).filter(_.serviceName == serviceName2)

    "delete applied configs correctly" in:
      val deploymentConfigA1: DeploymentConfig =
        DeploymentConfig(serviceName = ServiceName("A1"),
          environment    = Environment.Production,
          applied        = true,
          slots          = 1,
          instances      = 1,
          zone           = "-",
          deploymentType = "-",
          envVars        = Map.empty,
          jvm            = Map.empty,
          artefactName   = None
        )

      val deploymentConfigA2: DeploymentConfig =
        deploymentConfigA1.copy(serviceName = ServiceName("A2"))

      val deploymentConfigA3: DeploymentConfig =
        deploymentConfigA1.copy(serviceName = ServiceName("A3"))

      (for
        _             <- repository.add(deploymentConfigA1)
        _             <- repository.add(deploymentConfigA2)
        _             <- repository.add(deploymentConfigA3)
        _             <- repository.delete(deploymentConfigA1.serviceName, deploymentConfigA1.environment, deploymentConfigA1.applied)
        updatedConfig <- repository.find(
                           applied      = true,
                           environments = Seq(Environment.Production),
                           serviceNames = Seq(ServiceName("A1"), ServiceName("A2"), ServiceName("A3"))
                         )
        _             =  updatedConfig should contain theSameElementsAs:
                           Seq(
                             deploymentConfigA2,
                             deploymentConfigA3,
                           )
       yield ()
      ).futureValue

  def mkDeploymentConfig(
    serviceName : ServiceName,
    environment : Environment,
    applied     : Boolean,
    artefactName: Option[ArtefactName] = Some(ArtefactName("artefactName"))
  ): DeploymentConfig =
    DeploymentConfig(
      serviceName    = serviceName,
      artefactName   = artefactName,
      environment    = environment,
      zone           = "public",
      deploymentType = "microservice",
      slots          = 1,
      instances      = 1,
      envVars        = Map.empty,
      jvm            = Map.empty,
      applied        = applied
    )

  def toBson(deploymentConfig: DeploymentConfig): BsonDocument =
    BsonDocument(
      "name"         -> deploymentConfig.serviceName.asString,
      "artefactName" -> deploymentConfig.artefactName.map(_.asString),
      "environment"  -> deploymentConfig.environment.asString,
      "zone"         -> deploymentConfig.zone,
      "type"         -> deploymentConfig.deploymentType,
      "slots"        -> deploymentConfig.slots.toString,
      "instances"    -> deploymentConfig.instances.toString
    )
