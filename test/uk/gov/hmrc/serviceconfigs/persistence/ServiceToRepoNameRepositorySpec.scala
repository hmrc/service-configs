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
     with DefaultPlayMongoRepositorySupport[ServiceToRepoName] {

  override lazy val repository = new ServiceToRepoNameRepository(mongoComponent)

  private val serviceA  = ServiceName("serviceNameA")
  private val artefactA = ArtefactName("artefactNameA")
  private val repoA     = RepoName("repoNameA")

  private val serviceB  = ServiceName("serviceNameB")
  private val artefactB = ArtefactName("artefactNameB")
  private val repoB     = RepoName("repoNameB")

  private val seed = Seq(
    ServiceToRepoName(serviceA, artefactA, repoA)
  )

  "ServiceToRepoNameRepository" should {
    "return the repo name for a given service name" in {
      repository.putAll(seed).futureValue
      repository.findRepoName(serviceName = Some(serviceA)).futureValue shouldBe Some(repoA)
    }

    "return None when no service to repo mapping is found" in {
      repository.putAll(seed).futureValue
      repository.findRepoName(serviceName = Some(serviceB)).futureValue shouldBe None
    }

    "return the repo name for a given artefact name" in {
      repository.putAll(seed).futureValue
      repository.findRepoName(artefactName = Some(artefactA)).futureValue shouldBe Some(repoA)
    }

    "return None when no artefact to repo mapping is found" in {
      repository.putAll(seed).futureValue
      repository.findRepoName(artefactName = Some(artefactB)).futureValue shouldBe None
    }

    "clear the collection and refresh" in {
      repository.putAll(seed).futureValue

      val latest: Seq[ServiceToRepoName] =
        Seq(
          ServiceToRepoName(serviceA, artefactA, repoA),
          ServiceToRepoName(serviceB, artefactB, repoB)
        )

      repository.putAll(latest).futureValue

      repository.collection.find().toFuture().futureValue shouldBe List(
          ServiceToRepoName(serviceA, artefactA, repoA),
          ServiceToRepoName(serviceB, artefactB, repoB)
        )
    }
  }
}
