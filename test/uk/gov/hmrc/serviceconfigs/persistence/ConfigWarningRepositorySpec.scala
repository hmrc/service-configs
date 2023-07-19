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
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.service.ConfigWarning
import uk.gov.hmrc.serviceconfigs.service.ConfigService.RenderedConfigSourceValue

import scala.concurrent.ExecutionContext.Implicits.global

class ConfigWarningRepositorySpec
  extends AnyWordSpec
     with Matchers
     with DefaultPlayMongoRepositorySupport[ConfigWarning] {

  override protected val repository = new ConfigWarningRepository(mongoComponent)

  "ConfigWarningRepository" should {
    val env1         = Environment.Production
    val env2         = Environment.Development
    val serviceName1 = ServiceName("service1")
    val serviceName2 = ServiceName("service2")

    "putAll" in {
      val warning1 = ConfigWarning(env1, serviceName1, "k1", toValue("v1", source = "source2", sourceUrl = Some("sourceUrl")), "Unencrypted")
      val warning2 = ConfigWarning(env1, serviceName2, "k2", toValue("v2"), "NotOverriding")
      val warning3 = ConfigWarning(env2, serviceName1, "k3", toValue("v3"), "NotOverriding")
      val warning4 = ConfigWarning(env2, serviceName2, "k4", toValue("v4"), "NotOverriding")
      repository.putAll(Seq(warning1, warning2, warning3, warning4)).futureValue
      findAll().futureValue shouldBe Seq(warning1, warning2, warning3, warning4)

      val warning5 = ConfigWarning(env1, serviceName1, "k5", toValue("v5"), "Unencrypted")
      val warning6 = ConfigWarning(env1, serviceName2, "k6", toValue("v6"), "NotOverriding")
      repository.putAll(Seq(warning5, warning6)).futureValue
      findAll().futureValue shouldBe Seq(warning5, warning6)
    }

    "find" in {
      val warning1 = ConfigWarning(env1, serviceName1, "k1", toValue("v1"), "Unencrypted")
      val warning2 = ConfigWarning(env1, serviceName2, "k2", toValue("v2"), "NotOverriding")
      val warning3 = ConfigWarning(env2, serviceName1, "k3", toValue("v3"), "NotOverriding")
      val warning4 = ConfigWarning(env2, serviceName2, "k4", toValue("v4"), "NotOverriding")
      repository.putAll(Seq(warning1, warning2, warning3, warning4)).futureValue

      repository.find(environments = Some(Seq(env1)), serviceNames = None                   ).futureValue shouldBe Seq(warning1, warning2)
      repository.find(environments = Some(Seq(env2)), serviceNames = None                   ).futureValue shouldBe Seq(warning3, warning4)
      repository.find(environments = None           , serviceNames = Some(Seq(serviceName1))).futureValue shouldBe Seq(warning1, warning3)
      repository.find(environments = None           , serviceNames = Some(Seq(serviceName2))).futureValue shouldBe Seq(warning2, warning4)
      repository.find(environments = None           , serviceNames = Some(Seq(serviceName2))).futureValue shouldBe Seq(warning2, warning4)
      repository.find(environments = Some(Seq(env1)), serviceNames = Some(Seq(serviceName1))).futureValue shouldBe Seq(warning1)
    }
  }

  private def toValue(value: String, source: String = "source", sourceUrl: Option[String] = None): RenderedConfigSourceValue =
    RenderedConfigSourceValue(source = source, sourceUrl = sourceUrl, value)
}
