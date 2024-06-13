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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.mongo.test.DefaultPlayMongoRepositorySupport
import uk.gov.hmrc.serviceconfigs.model.DependencyConfig

import scala.concurrent.ExecutionContext.Implicits.global

class DependencyConfigRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[DependencyConfig] {

  override val repository: DependencyConfigRepository = new DependencyConfigRepository(mongoComponent)

  override protected def checkTtlIndex: Boolean =
    // disabled until we address cleanup
    false

  "DependencyConfigRepository" should {
    "work correctly" in {
      val dc1 = mkDependencyConfig(1)
      val dc2 = mkDependencyConfig(2)

      repository.add(dc1).futureValue
      repository.add(dc2).futureValue

      repository.getAllEntries().futureValue shouldBe Seq(dc1, dc2)

      repository.getDependencyConfig(group = dc1.group, artefact = dc1.artefact, version = dc1.version).futureValue shouldBe Some(dc1)
    }
  }

  def mkDependencyConfig(i: Int): DependencyConfig =
    DependencyConfig(
      group    = s"g$i",
      artefact = s"a$i",
      version  = s"v$i",
      configs  = Map(s"k${i}1"-> s"v${i}1", s"k${i}2" -> s"v${i}2")
    )
}
