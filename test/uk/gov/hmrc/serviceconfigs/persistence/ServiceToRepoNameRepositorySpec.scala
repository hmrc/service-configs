/*
 * Copyright 2024 HM Revenue & Customs
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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.{ArtefactName, RepoName, ServiceName, ServiceToRepoName}

import scala.concurrent.ExecutionContext.Implicits.global

class ServiceToRepoNameRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[ServiceToRepoName]:

  override val repository: ServiceToRepoNameRepository =
    ServiceToRepoNameRepository(mongoComponent)

  private val mappingA =
    ServiceToRepoName(
      serviceName  = ServiceName("serviceNameA")
    , artefactName = ArtefactName("artefactNameA")
    , repoName     = RepoName("repoNameA")
    )

  private val mappingB =
    ServiceToRepoName(
      serviceName  = ServiceName("serviceNameB")
    , artefactName = ArtefactName("artefactNameB")
    , repoName     = RepoName("repoNameB")
    )

  "ServiceToRepoNameRepository" should:
    "return all mappings" in:
      repository.putAll(Seq(mappingA, mappingB)).futureValue
      repository.findServiceToRepoNames().futureValue shouldBe Seq(mappingA, mappingB)

    "return the repo name for a given service name" in:
      repository.putAll(Seq(mappingA)).futureValue
      repository.findServiceToRepoNames(serviceName = Some(mappingA.serviceName)).futureValue shouldBe Seq(mappingA)

    "return None when no service to repo mapping is found" in:
      repository.putAll(Seq(mappingA)).futureValue
      repository.findServiceToRepoNames(serviceName = Some(mappingB.serviceName)).futureValue shouldBe Seq.empty

    "return the repo name for a given artefact name" in:
      repository.putAll(Seq(mappingA)).futureValue
      repository.findServiceToRepoNames(artefactName = Some(mappingA.artefactName)).futureValue shouldBe Seq(mappingA)

    "return None when no artefact to repo mapping is found" in:
      repository.putAll(Seq(mappingA)).futureValue
      repository.findServiceToRepoNames(artefactName = Some(mappingB.artefactName)).futureValue shouldBe Seq.empty


    "clear the collection and refresh" in:
      repository.putAll(Seq(mappingA)).futureValue

      repository.putAll(Seq(mappingA, mappingB)).futureValue

      findAll().futureValue shouldBe Seq(mappingA, mappingB)
