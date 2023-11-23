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

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.collection.immutable.Document
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.serviceconfigs.controller.ConfigController
import uk.gov.hmrc.serviceconfigs.persistence.{DeployedConfigRepository, LatestConfigRepository, SlugInfoRepository}
import uk.gov.hmrc.serviceconfigs.parser.{ConfigValue, ConfigValueType}
import uk.gov.hmrc.serviceconfigs.model.{Environment, MongoSlugInfoFormats, ServiceName, SlugInfo, Version}

import java.time.Instant
import java.time.temporal.ChronoUnit

class ConfigServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterAll
     with GuiceOneAppPerSuite
     with WireMockSupport
     with MongoSupport {
  import ConfigService._

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.artefact-processor.host" -> wireMockHost
      , "microservice.services.artefact-processor.port" -> wireMockPort
      , "play.http.requestHandler"                      -> "play.api.http.DefaultHttpRequestHandler"
      , "mongodb.uri"                                   -> mongoUri
      , "github.open.api.rawurl"                        -> s"http://$wireMockHost:$wireMockPort"
      )
      .build()

  private val configService = app.injector.instanceOf[ConfigService]

  private val slugInfoCollection       = mongoDatabase.getCollection(SlugInfoRepository.collectionName)
  private val latestConfigCollection   = mongoDatabase.getCollection(LatestConfigRepository.collectionName)
  private val deployedConfigCollection = mongoDatabase.getCollection(DeployedConfigRepository.collectionName)

  override def beforeEach(): Unit =
    dropDatabase()

  "ConfigService.configByEnvironment" should {
    List(true, false).foreach { latest =>
      s"show config changes per key for each environment for latest $latest" in {
        val serviceName = ServiceName("test-service")
        setup(serviceName, latest)

        val configByEnvironment = configService.configByEnvironment(serviceName, environments = Nil, version = None, latest = latest)

        import ConfigController._

        val applicationConf = """
          { "source": "applicationConf",
            "sourceUrl": "https://github.com/hmrc/test-service/blob/main/conf/application.conf",
            "entries": {"a": "1", "b": "2", "c": "1", "list": "[1,2]"} }"""

        def other(env: String) = s"""
          "$env": [
            { "source": "loggerConf", "sourceUrl": "https://github.com/hmrc/test-service/blob/main/conf/application-json-logger.xml", "entries": {} },
            { "source": "referenceConf", "entries": {} },
            { "source": "applicationConf", "sourceUrl": "https://github.com/hmrc/test-service/blob/main/conf/application.conf", "entries": {} },
            { "source": "baseConfig", "sourceUrl": "https://github.com/hmrc/app-config-base/blob/main/test-service.conf", "entries": {} },
            { "source": "appConfigCommonOverridable", "entries": {} },
            { "source": "appConfigEnvironment", "sourceUrl": "https://github.com/hmrc/app-config-$env/blob/main/test-service.yaml", "entries": {}},
            { "source": "appConfigCommonFixed", "entries": {} },
            { "source": "base64", "entries": {} }
          ]"""

        // val localEntries = """{"a": "1", "b": "2", "c": "1", "list": "[1,2]"}"""
        Json.toJson(configByEnvironment.futureValue) shouldBe Json.parse(s"""{
          "local": [
            { "source": "referenceConf", "entries": {}},
            $applicationConf,
            { "source": "base64", "entries": {} }
          ],
          "development": [
            { "source": "loggerConf", "sourceUrl": "https://github.com/hmrc/test-service/blob/main/conf/application-json-logger.xml", "entries": {} },
            { "source": "referenceConf", "entries": {} },
            $applicationConf,
            { "source": "baseConfig", "sourceUrl": "https://github.com/hmrc/app-config-base/blob/main/test-service.conf", "entries": {} },
            { "source": "appConfigCommonOverridable", "entries": {} },
            { "source": "appConfigEnvironment", "sourceUrl": "https://github.com/hmrc/app-config-development/blob/main/test-service.yaml", "entries": {
                "list.2": "3",
                "a": "3",
                "b": "4",
                "c": "3",
                "list": "<<SUPPRESSED>>"
            }},
            { "source": "appConfigCommonFixed", "entries": {} },
            { "source": "base64", "entries": {} }
          ],
          "qa": [
            { "source": "loggerConf", "sourceUrl": "https://github.com/hmrc/test-service/blob/main/conf/application-json-logger.xml", "entries": {} },
            { "source": "referenceConf", "entries": {} },
            $applicationConf,
            { "source": "baseConfig", "sourceUrl": "https://github.com/hmrc/app-config-base/blob/main/test-service.conf", "entries": {} },
            { "source": "appConfigCommonOverridable", "entries": {} },
            { "source": "appConfigEnvironment", "sourceUrl": "https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml", "entries": {
                "a": "<<SUPPRESSED>>",
                "d.base64": "Mg==",
                "c": "<<SUPPRESSED>>",
                "d": "<<SUPPRESSED>>",
                "a.b": "6",
                "c.b": "6"
            }},
            { "source": "appConfigCommonFixed", "entries": {} },
            { "source": "base64", "entries": { "d": "2" }}
          ],
          ${other("integration")},
          ${other("staging")},
          ${other("externaltest")},
          ${other("production")}
        }""")
      }
    }

    "preserve loggers from app-config-env" in {
      val serviceName = ServiceName("test-service")
      val now      = Instant.now()
      val slugInfo = SlugInfo(
        uri               = "some/uri"
      , created           = now
      , name              = serviceName
      , version           = Version(major = 0, minor = 1, patch = 0, original = "0.1.0")
      , classpath         = ""  // not stored in Mongo - used to order dependencies before storing
      , dependencies      = Nil
      , applicationConfig = List("logger.resource =\"logback.conf\"").mkString("\n")
      , includedAppConfig = Map.empty
      , loggerConfig      = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<configuration>\n\n    <appender name=\"STDOUT\" class=\"ch.qos.logback.core.ConsoleAppender\">\n        <encoder class=\"uk.gov.hmrc.play.logging.JsonEncoder\"/>\n    </appender>\n\n    <root level=\"WARN\">\n        <appender-ref ref=\"STDOUT\"/>\n    </root>\n</configuration>\n"
      , slugConfig        = ""
      )

      val appConfigQa = """
        |hmrc_config.logger.uk.gov: DEBUG
        |hmrc_config.logger.uk.gov.hmrc: DEBUG
        """.stripMargin
      withAppConfigEnvForHEAD("qa", serviceName, appConfigQa)

      implicit val sif = MongoSlugInfoFormats.slugInfoFormat
      slugInfoCollection
        .insertOne {
          val json =
            Json.toJson(slugInfo).as[JsObject] ++
            Json.obj(
              "latest"       -> JsBoolean(true)
            , "qa"           -> JsBoolean(true)
            )
          Document(json.toString)
        }.toFuture()
          .futureValue

      val configByEnvironment = configService.configByEnvironment(serviceName, environments = Nil, version = None, latest = true)

      import ConfigController._


      (Json.toJson(configByEnvironment.futureValue) \ "qa").as[JsValue] shouldBe Json.parse(s"""[
        { "source": "loggerConf", "sourceUrl": "https://github.com/hmrc/test-service/blob/main/conf/application-json-logger.xml", "entries": {} },
        { "source": "referenceConf", "entries": {} },
        { "source": "applicationConf", "sourceUrl": "https://github.com/hmrc/test-service/blob/main/conf/application.conf", "entries": {
            "logger.resource": "logback.conf"
        }},
        { "source": "baseConfig", "sourceUrl": "https://github.com/hmrc/app-config-base/blob/main/test-service.conf", "entries": {} },
        { "source": "appConfigCommonOverridable", "entries": {} },
        { "source": "appConfigEnvironment", "sourceUrl": "https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml", "entries": {
            "logger.\\"uk.gov.hmrc\\"":"DEBUG",
            "logger.\\"uk.gov\\"":"DEBUG"
        }},
        { "source": "appConfigCommonFixed", "entries": {} },
        { "source": "base64", "entries": {}}
      ]""")
    }
  }

  "ConfigService.resultingConfig" should {
    "flatten ConfigByEnvironment" in {
      val serviceName = ServiceName("test-service")
      val latest = false
      setup(serviceName, latest)

      val cseDev = configService.configSourceEntries(ConfigEnvironment.ForEnvironment(Environment.Development), serviceName, version = None, latest).futureValue

      configService.resultingConfig(cseDev) shouldBe Map(
        "list.2" -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), ConfigValue("3"))
      , "a"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), ConfigValue("3"))
      , "b"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), ConfigValue("4"))
      , "c"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), ConfigValue("3"))
      , "list"   -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), ConfigValue.Suppressed)
      )

      val csaQa = configService.configSourceEntries(ConfigEnvironment.ForEnvironment(Environment.QA), serviceName, version = None, latest).futureValue
      configService.resultingConfig(csaQa) shouldBe Map(
        "a"        -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , ConfigValue.Suppressed)
      , "c"        -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , ConfigValue.Suppressed)
      , "a.b"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , ConfigValue("6"))
      , "c.b"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , ConfigValue("6"))
      , "d.base64" -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , ConfigValue("Mg=="))
      , "b"        -> ConfigSourceValue("applicationConf"     , Some("https://github.com/hmrc/test-service/blob/main/conf/application.conf"), ConfigValue("2"))
      , "list"     -> ConfigSourceValue("applicationConf"     , Some("https://github.com/hmrc/test-service/blob/main/conf/application.conf"), ConfigValue("[1,2]", ConfigValueType.List))
      , "d"        -> ConfigSourceValue("base64"              , None                                                                        , ConfigValue("2"))
      )
    }
  }

  def withAppConfigEnvForHEAD(env: String, serviceName: ServiceName, content: String): Unit =
    latestConfigCollection
      .insertOne(Document(
        "repoName" -> s"app-config-$env",
        "fileName" -> s"${serviceName.asString}.yaml",
        "content"  -> content
      ))
      .toFuture().futureValue

  def withAppConfigEnvForDeployment(env: String, serviceName: ServiceName, content: String, lastUpdated: Instant): Unit = {
    deployedConfigCollection
      .insertOne(Document(
        "serviceName"       -> serviceName.asString,
        "environment"       -> env,
        "deploymentId"      -> "deploymentId",
        "configId"          -> "configId",
        //"appConfigBase"   -> None,
        //"appConfigCommon" -> None
        "appConfigEnv"      -> content,
        "lastUpdated"       -> BsonDocument("$date" -> BsonDocument("$numberLong" -> lastUpdated.toEpochMilli.toString))
      ))
      .toFuture().futureValue
  }

  def setup(serviceName: ServiceName, latest: Boolean): Unit = {
    val now      = Instant.now()
    val slugInfo = SlugInfo(
      uri               = "some/uri"
    , created           = now
    , name              = serviceName
    , version           = Version(major = 0, minor = 1, patch = 0, original = "0.1.0")
    , classpath         = ""  // not stored in Mongo - used to order dependencies before storing
    , dependencies      = Nil
    , applicationConfig = s"\na=1\nb=2\nc=$${a}\nlist=[1,2]\n"
    , includedAppConfig = Map.empty
    , loggerConfig      = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<configuration>\n\n    <appender name=\"STDOUT\" class=\"ch.qos.logback.core.ConsoleAppender\">\n        <encoder class=\"uk.gov.hmrc.play.logging.JsonEncoder\"/>\n    </appender>\n\n    <root level=\"WARN\">\n        <appender-ref ref=\"STDOUT\"/>\n    </root>\n</configuration>\n"
    , slugConfig        = ""
    )

    val appConfigDev =
      """
        |hmrc_config.a: 3
        |hmrc_config.b: 4
        |hmrc_config.list.2: 3
      """.stripMargin
    val appConfigQa = """
      |hmrc_config.a.b: 6
      |hmrc_config.d: 1
      |hmrc_config.d.base64: Mg==
      """.stripMargin
    if (latest) {
      withAppConfigEnvForHEAD("development", serviceName, appConfigDev)
      withAppConfigEnvForHEAD("qa"         , serviceName, appConfigQa)
    } else {
      withAppConfigEnvForDeployment("development", serviceName, appConfigDev, now.minus(2, ChronoUnit.DAYS))
      withAppConfigEnvForDeployment("qa"         , serviceName, appConfigQa , now.minus(2, ChronoUnit.DAYS))
    }

    implicit val sif = MongoSlugInfoFormats.slugInfoFormat
    slugInfoCollection
      .insertOne {
        val json =
          Json.toJson(slugInfo).as[JsObject] ++
          Json.obj(
            "latest"       -> JsBoolean(true)
          , "development"  -> JsBoolean(true)
          , "qa"           -> JsBoolean(true)
          )
        Document(json.toString)
      }.toFuture()
        .futureValue
  }
}
