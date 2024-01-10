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
import uk.gov.hmrc.serviceconfigs.connector.TeamsAndRepositoriesConnector.Repo
import uk.gov.hmrc.serviceconfigs.model.Environment.{Development, QA}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, DeploymentConfigGrouped, DeploymentEnvironment, Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.persistence.model.Sort

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
      repository.find().futureValue shouldBe (developmentDeploymentConfigs ++ productionDeploymentConfigs)

      repository.find(Seq(Environment.Development)).futureValue shouldBe developmentDeploymentConfigs
      repository.find(Seq(Environment.Production) ).futureValue shouldBe productionDeploymentConfigs

      val developmentDeploymentConfigs2 = Seq(
        mkDeploymentConfig(ServiceName("service3"), Environment.Development),
        mkDeploymentConfig(ServiceName("service4"), Environment.Development)
      )

      repository.replaceEnv(Environment.Development, developmentDeploymentConfigs2.map(toBson)).futureValue

      repository.find(Seq(Environment.Development)).futureValue shouldBe developmentDeploymentConfigs2
      repository.find(Seq(Environment.Production) ).futureValue shouldBe productionDeploymentConfigs
    }

    "find one by matching service name and environment" in {
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

      repository.find(Seq(Environment.Development), Some(serviceName1)).futureValue shouldBe developmentDeploymentConfigs.filter(_.serviceName == serviceName1)
      repository.find(Seq(Environment.Production) , Some(serviceName1)).futureValue shouldBe productionDeploymentConfigs .filter(_.serviceName == serviceName1)
    }

    "find by matching service name" in {
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

      repository.find(serviceName = Some(serviceName2)).futureValue shouldBe (developmentDeploymentConfigs ++ productionDeploymentConfigs).filter(_.serviceName == serviceName2)
    }

    "find all configs and group" in {
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")

      insert(mkDeploymentConfig(serviceName1, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName1, Environment.QA)).futureValue
      insert(mkDeploymentConfig(serviceName2, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName2, Environment.QA)).futureValue

      val expectedResults = List(
        DeploymentConfigGrouped(
          ServiceName("serviceName1"),
            List(
              DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1),
              DeploymentEnvironment(Some("artefactName"), QA, "public", "microservice", 1, 1)
            )
        ),
        DeploymentConfigGrouped(
          ServiceName("serviceName2"),
            List(
              DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1),
              DeploymentEnvironment(Some("artefactName"), QA, "public", "microservice", 1, 1)
            )
        )
      )

      repository.findGrouped(None, None, None).futureValue shouldBe expectedResults
    }

    "find all configs and group filtering by service name" in {
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")

      insert(mkDeploymentConfig(serviceName1, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName1, Environment.QA)).futureValue
      insert(mkDeploymentConfig(serviceName2, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName2, Environment.QA)).futureValue

      val expectedResults = List(
        DeploymentConfigGrouped(
          ServiceName("serviceName1"),
          List(
            DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1),
            DeploymentEnvironment(Some("artefactName"), QA, "public", "microservice", 1, 1)
          )
        )
      )

      repository.findGrouped(Some(serviceName1), None, None).futureValue shouldBe expectedResults
    }

    "find all configs and group filtering by partial service name" in {
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")
      val serviceName3 = ServiceName("otherServiceName")

      insert(mkDeploymentConfig(serviceName1, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName2, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName3, Environment.Development)).futureValue

      val expectedResults = List(
        DeploymentConfigGrouped(
          ServiceName("serviceName1"),
          List(
            DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1)
           )
        ),
        DeploymentConfigGrouped(
          ServiceName("serviceName2"),
          List(
            DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1)
          )
        )
      )

      repository.findGrouped(Some(ServiceName("serviceName")), None, None).futureValue shouldBe expectedResults
    }

    "find all configs and group filtering by provided repo list" in {
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")
      val serviceName3 = ServiceName("otherServiceName")

      insert(mkDeploymentConfig(serviceName1, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName2, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName3, Environment.Development)).futureValue

      val expectedResults = List(
        DeploymentConfigGrouped(
          ServiceName("serviceName1"),
          List(
            DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1)
          )
        ),
        DeploymentConfigGrouped(
          ServiceName("serviceName2"),
          List(
            DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1)
          )
        )
      )

      repository.findGrouped(None, Some(Seq(Repo("serviceName1"), Repo("serviceName2"))), None).futureValue shouldBe expectedResults
    }

    "find all configs, group by service name and sort by given param" in {
      val serviceName1 = ServiceName("serviceName1")
      val serviceName2 = ServiceName("serviceName2")

      insert(mkDeploymentConfig(serviceName1, Environment.Development)).futureValue
      insert(mkDeploymentConfig(serviceName2, Environment.Development)).futureValue

      val expectedResults = List(
        DeploymentConfigGrouped(
          ServiceName("serviceName2"),
          List(
            DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1)
          )
        ),
        DeploymentConfigGrouped(
          ServiceName("serviceName1"),
          List(
            DeploymentEnvironment(Some("artefactName"), Development, "public", "microservice", 1, 1)
          )
        )
      )

      repository.findGrouped(None, None, Some("applicationName.dsc")).futureValue shouldBe expectedResults
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
