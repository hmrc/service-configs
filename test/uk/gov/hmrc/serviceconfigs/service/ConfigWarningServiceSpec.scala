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

package uk.gov.hmrc.serviceconfigs.service

import org.mockito.scalatest.MockitoSugar
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.serviceconfigs.model.{Environment, ServiceName}
import uk.gov.hmrc.serviceconfigs.parser.{ConfigValue, ConfigValueType}
import uk.gov.hmrc.serviceconfigs.service.ConfigService.{ConfigSourceEntries, ConfigSourceValue}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class ConfigWarningServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with MockitoSugar
     with IntegrationPatience {

  private implicit val hc: HeaderCarrier = HeaderCarrier()

  "ConfigWarningService.warnings" when {
    "detecting NotOverridden" should {
      "detect NotOverriding" in new Setup {
        val key1 = "k1"
        val key2 = "k2"
        val value1 = ConfigValue("v1")
        val value2 = ConfigValue("v2")

        when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(
            ConfigSourceEntries("baseConfig"          , None, Map(key1 -> value1)),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key2 -> value2))
          )))

        service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq(
          ConfigWarning(key1, ConfigSourceValue("baseConfig"          , None, value1), "NotOverriding"),
          ConfigWarning(key2, ConfigSourceValue("appConfigEnvironment", None, value2), "NotOverriding")
        )
      }

      "ignore system properties" in new Setup {
        val value = ConfigValue("v")

        when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(
            ConfigSourceEntries("baseConfig"          , None, Map("logger.application" -> value)),
            ConfigSourceEntries("baseConfig"          , None, Map("java.asd"           -> value)),
            ConfigSourceEntries("baseConfig"          , None, Map("javax.asd"          -> value)),
            ConfigSourceEntries("baseConfig"          , None, Map("user.timezone"      -> value))
          )))

        service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq.empty
      }

      "ignore list positional notation" in new Setup {
        val value = ConfigValue("v")
        when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(
            ConfigSourceEntries("referenceConf"       , None, Map("list"   -> value)),
            ConfigSourceEntries("appConfigEnvironment", None, Map("list.0" -> value))
          )))

        service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq.empty
      }

      "ignore list positional notation 2" in new Setup {
        val value = ConfigValue("v")
        when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(
            ConfigSourceEntries("referenceConf"       , None, Map("list"       -> value)),
            ConfigSourceEntries("appConfigEnvironment", None, Map("list.0.key" -> value))
          )))

        service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq.empty
      }

      "ignore base64 overrides" in new Setup {
        val value = ConfigValue("v")
        when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(
            ConfigSourceEntries("referenceConf"       , None, Map("k"   -> value)),
            ConfigSourceEntries("appConfigEnvironment", None, Map("k.base64" -> value))
          )))

        service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq.empty
      }
    }

    "detecting config type changes" should {
      "detect Type changes" in new Setup {
        val key1 = "k1"
        val key2 = "k2"
        val key3 = "k3"

        when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(
            // list -> simple
            ConfigSourceEntries("applicationConf"     , None, Map(key1 -> ConfigValue("[]", ConfigValueType.List))),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key1 -> ConfigValue("s" , ConfigValueType.SimpleValue))),
            // object -> simple
            ConfigSourceEntries("applicationConf"     , None, Map(key2 -> ConfigValue("{}", ConfigValueType.Object))),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key2 -> ConfigValue("s" , ConfigValueType.SimpleValue))),
            // list -> object
            ConfigSourceEntries("applicationConf"     , None, Map(key3 -> ConfigValue("[]", ConfigValueType.List))),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key3 -> ConfigValue("{}", ConfigValueType.Object)))
          )))

        service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq(
          ConfigWarning(key1, ConfigSourceValue("appConfigEnvironment", None, ConfigValue("s" , ConfigValueType.SimpleValue)), "TypeChange"),
          ConfigWarning(key2, ConfigSourceValue("appConfigEnvironment", None, ConfigValue("s" , ConfigValueType.SimpleValue)), "TypeChange"),
          ConfigWarning(key3, ConfigSourceValue("appConfigEnvironment", None, ConfigValue("{}", ConfigValueType.Object     )), "TypeChange")
        )
      }

      "ignore Null, Unmerged and Suppressed" in new Setup {
        val key1 = "k1"
        val key2 = "k2"

        when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
          .thenReturn(Future.successful(Seq(
            // simple -> null
            ConfigSourceEntries("applicationConf"     , None, Map(key1 -> ConfigValue("s", ConfigValueType.SimpleValue))),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key1 -> ConfigValue.Null)),
            // null -> simple
            ConfigSourceEntries("applicationConf"     , None, Map(key2 -> ConfigValue.Null)),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key2 -> ConfigValue("s", ConfigValueType.SimpleValue))),
            // simple -> unmerged
            ConfigSourceEntries("applicationConf"     , None, Map(key1 -> ConfigValue("s"   , ConfigValueType.SimpleValue))),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key1 -> ConfigValue("${s}", ConfigValueType.Unmerged))),
            // unmerged -> simple
            ConfigSourceEntries("applicationConf"     , None, Map(key2 -> ConfigValue("${s}", ConfigValueType.Unmerged))),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key2 -> ConfigValue("s"   , ConfigValueType.SimpleValue))),
            // simple -> suppressed
            ConfigSourceEntries("applicationConf"     , None, Map(key2 -> ConfigValue("s"   , ConfigValueType.SimpleValue))),
            ConfigSourceEntries("appConfigEnvironment", None, Map(key2 -> ConfigValue.Suppressed))
          )))

        service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq.empty
      }
    }

    "detect Localhost" in new Setup {
      val key1 = "url"
      val key2 = "array"
      val value1 = ConfigSourceValue("baseConfig", None, ConfigValue("[\"^.*\\.service$\",\"^localhost$\"]", ConfigValueType.List))
      val value2 = ConfigSourceValue("baseConfig", None, ConfigValue("http://localhost:123"))

      when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
        .thenReturn(Map(
          key1 -> value1,
          key2 -> value2
        ))

      service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq(
        ConfigWarning(key2, value2, "Localhost"),
        ConfigWarning(key1, value1, "Localhost")
      )
    }

    "detect DEBUG" in new Setup {
      val key   = "logger.application"
      val value = ConfigSourceValue("baseConfig", None, ConfigValue("DEBUG"))

      when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
        .thenReturn(Map(
          key -> value
        ))

      service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq(
        ConfigWarning(key, value, "DEBUG")
      )
    }

    "detect TestOnlyRoutes" in new Setup {
      val key1  = "application.router"
      val key2  = "play.http.router"
      val value = ConfigSourceValue("baseConfig", None, ConfigValue("testOnlyDoNotUseInAppConf.Routes"))

      when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
        .thenReturn(Map(
          key1 -> value,
          key2 -> value,
        ))

      service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq(
        ConfigWarning(key1, value, "TestOnlyRoutes"),
        ConfigWarning(key2, value, "TestOnlyRoutes")
      )
    }

    "detect reactiveMongoConfig" in new Setup {
      val key   = "mongodb.uri"
      val value = ConfigSourceValue("baseConfig", None, ConfigValue("mongodb://protected-mongo-1:27017,protected-mongo-2:27017,protected-mongo-3:27017/service?writeConcernW=2&writeConcernJ=true&writeConcernTimeout=5000&sslEnabled=true"))

      when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
        .thenReturn(Map(
          key -> value
        ))

      service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq(
        ConfigWarning(key, value, "ReactiveMongoConfig")
      )
    }

    "detect unencrypted config" in new Setup {
      val key   = "mykey"
      val value = ConfigSourceValue("baseConfig", None, ConfigValue("ASD=="))

      when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
        .thenReturn(Map(
          key -> value
        ))

      service.warnings(Environment.Production, ServiceName("service"), latest = true).futureValue shouldBe Seq(
        ConfigWarning(key, value, "Unencrypted")
      )
    }
  }

  trait Setup {
    val mockedConfigService = mock[ConfigService]

    when(mockedConfigService.configSourceEntries(any[ConfigService.ConfigEnvironment], any[ServiceName], any[Boolean])(any[HeaderCarrier]))
      .thenReturn(Future.successful(Seq.empty))

    when(mockedConfigService.resultingConfig(any[Seq[ConfigSourceEntries]]))
      .thenReturn(Map.empty)

    val service =
      new ConfigWarningService(
        mockedConfigService
      )
  }
}
