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

import org.mongodb.scala.bson.{BsonDateTime, BsonDocument}
import org.mongodb.scala.bson.collection.immutable.Document
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats
import uk.gov.hmrc.serviceconfigs.model.{SlugInfo, Version}
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.serviceconfigs.persistence.{LatestConfigRepository, SlugInfoRepository}
import uk.gov.hmrc.serviceconfigs.model.{Environment, MongoSlugInfoFormats, ServiceName}

import java.time.Instant
import uk.gov.hmrc.serviceconfigs.persistence.DeployedConfigRepository

import java.time.temporal.ChronoUnit

class ConfigServiceSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with BeforeAndAfterAll
     with GuiceOneAppPerSuite
     with WireMockSupport
     with MongoSupport
     with uk.gov.hmrc.serviceconfigs.ConfigJson {
  import ConfigService._

  implicit val hc: HeaderCarrier = HeaderCarrier()

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .configure(
        "microservice.services.artefact-processor.host" -> wireMockHost
      , "microservice.services.artefact-processor.port" -> wireMockPort
      , "play.http.requestHandler"                      -> "play.api.http.DefaultHttpRequestHandler"
      , "metrics.jvm"                                   -> false
      , "mongodb.uri"                                   -> mongoUri
      , "github.open.api.rawurl"                        -> s"http://$wireMockHost:$wireMockPort"
      )
      .build()

  private val configService = app.injector.instanceOf[ConfigService]

  private val slugInfoCollection       = mongoDatabase.getCollection(SlugInfoRepository.collectionName)
  private val latestConfigCollection   = mongoDatabase.getCollection(LatestConfigRepository.collectionName)
  private val deployedConfigCollection = mongoDatabase.getCollection(DeployedConfigRepository.collectionName)

  implicit val slugInfoFormat = MongoSlugInfoFormats.slugInfoFormat

  override def beforeEach(): Unit = {
    dropDatabase()
  }

  "ConfigService.configByKey" should {
    List(true, false).foreach { latest =>
      s"show config changes per key for each environment for latest $latest" in {
        val serviceName = ServiceName("test-service")
        setup(serviceName, latest)

        val configByKey = configService.configByKey(serviceName, latest = latest)

        val appConfUrl = "https://github.com/hmrc/test-service/blob/main/conf/application.conf"
        def appConfEnv(env: String) = s"https://github.com/hmrc/app-config-$env/blob/main/test-service.yaml"
        Json.toJson(configByKey.futureValue) shouldBe Json.parse(s"""
          {
          "a": {
            "local"      : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "1" }],
            "development": [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "1" },
                            { "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("development")}", "value": "3" }
                          ],
            "qa"         : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "1" },
                            { "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("qa")}"         , "value": "<<SUPPRESSED>>" }
                          ]
          },
          "a.b": {
            "qa"         : [{ "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("qa")}"         , "value": "6" } ]
          },
          "b": {
            "local"      : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "2" } ],
            "development": [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "2" },
                            { "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("development")}", "value": "4" }
                          ],
            "qa"         : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "2" }]
          },
          "c": {
            "local"      : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "1" }],
            "development": [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "1"},
                            { "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("development")}", "value": "3"}
                          ],
            "qa"         : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "1" },
                            { "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("qa")}"         , "value": "<<SUPPRESSED>>" }
                          ]
          },
          "c.b": {
            "qa"         : [{ "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("qa")}"         , "value": "6" }]
          },
          "d": {
            "qa"         : [{ "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("qa")}"         , "value": "<<SUPPRESSED>>" },
                            { "source": "base64"                                                           , "value": "2" }
                          ]
          },
          "d.base64": {
            "qa"         : [{ "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("qa")}"         , "value": "Mg=="}]
          },
          "list": {
            "local"      : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "[1,2]" }],
            "development": [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "[1,2]" },
                            { "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("development")}", "value": "<<SUPPRESSED>>" }
                          ],
            "qa"         : [{ "source": "applicationConf"     , "sourceUrl": "$appConfUrl"                 , "value": "[1,2]" }]
          },
          "list.2": {
            "development": [{ "source": "appConfigEnvironment", "sourceUrl": "${appConfEnv("development")}", "value": "3" }]
          }
        }
        """")
      }
    }
  }

  "ConfigService.resultingConfig" should {
    List(true, false).foreach { latest =>
      s"show config changes per key for each environment for latest $latest" in {
        val serviceName = ServiceName("test-service")
        setup(serviceName, latest)

        configService.resultingConfig(ConfigEnvironment.ForEnvironment(Environment.Development), serviceName, latest = latest).futureValue shouldBe Map(
          "list.2" -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), "3")
        , "a"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), "3")
        , "b"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), "4")
        , "c"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), "3")
        , "list"   -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-development/blob/main/test-service.yaml"), "<<SUPPRESSED>>")
        )

        configService.resultingConfig(ConfigEnvironment.ForEnvironment(Environment.QA), serviceName, latest = latest).futureValue shouldBe Map(
          "a"        -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , "<<SUPPRESSED>>")
        , "c"        -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , "<<SUPPRESSED>>")
        , "a.b"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , "6")
        , "c.b"      -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , "6")
        , "d.base64" -> ConfigSourceValue("appConfigEnvironment", Some("https://github.com/hmrc/app-config-qa/blob/main/test-service.yaml")   , "Mg==")
        , "b"        -> ConfigSourceValue("applicationConf"     , Some("https://github.com/hmrc/test-service/blob/main/conf/application.conf"), "2")
        , "list"     -> ConfigSourceValue("applicationConf"     , Some("https://github.com/hmrc/test-service/blob/main/conf/application.conf"), "[1,2]")
        , "d"        -> ConfigSourceValue("base64"              , None                                                                        , "2")
        )
      }
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

  def withAppConfigEnvForDeployment(env: String, serviceName: ServiceName, content: String): Unit = {
    deployedConfigCollection
      .insertOne(Document(
        "serviceName"       -> serviceName.asString,
        "environment"       -> env,
        "deploymentId"      -> "deploymentId",
        "configId"          -> "configId",
        //"appConfigBase"   -> None,
        //"appConfigCommon" -> None
        "appConfigEnv"      -> content,
        "lastUpdated"       -> BsonDocument("$date" -> BsonDocument("$numberLong" -> Instant.now().minus(2, ChronoUnit.DAYS).toEpochMilli.toString))
      ))
      .toFuture().futureValue
  }

  def setup(serviceName: ServiceName, latest: Boolean): Unit = {
    val slugInfo = SlugInfo(
      uri               = "some/uri"
    , created           = Instant.now
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
      withAppConfigEnvForDeployment("development", serviceName, appConfigDev)
      withAppConfigEnvForDeployment("qa"         , serviceName, appConfigQa)
    }

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
