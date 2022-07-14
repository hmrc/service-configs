/*
 * Copyright 2022 HM Revenue & Customs
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

import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json._
import org.mongodb.scala.bson.collection.immutable.Document

import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.http.test.WireMockSupport
import uk.gov.hmrc.serviceconfigs.model.{SlugInfo, Version}
import uk.gov.hmrc.mongo.test.MongoSupport
import uk.gov.hmrc.serviceconfigs.persistence.SlugInfoRepository
import uk.gov.hmrc.serviceconfigs.model.MongoSlugInfoFormats

import java.time.Instant
import scala.concurrent.ExecutionContext.Implicits.global

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
  private val slugInfoCollection = mongoDatabase.getCollection(SlugInfoRepository.collectionName)

  implicit val slugInfoFormat = MongoSlugInfoFormats.slugInfoFormat

  override def beforeEach(): Unit = {
    dropDatabase()
  }

  "ConfigService.configByKey" should {
    "shows config changes per key for each enviroment" in {
      val service = "test-service"
      val slugInfo = SlugInfo(
        uri               = "some/uri"
      , created           = Instant.now
      , name              = service
      , version           = Version(major = 0, minor = 1, patch = 0, original = "0.1.0")
      , classpath         = ""  // not stored in Mongo - used to order dependencies before storing
      , dependencies      = Nil
      , applicationConfig = s"\na=1\nb=2\nc=$${a}\nlist=[1,2]\n"
      , loggerConfig      = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<configuration>\n\n    <appender name=\"STDOUT\" class=\"ch.qos.logback.core.ConsoleAppender\">\n        <encoder class=\"uk.gov.hmrc.play.logging.JsonEncoder\"/>\n    </appender>\n\n    <root level=\"WARN\">\n        <appender-ref ref=\"STDOUT\"/>\n    </root>\n</configuration>\n"
      , slugConfig        = ""
      )

      stubFor(
        get(urlEqualTo(s"/hmrc/app-config-base/HEAD/$service.conf"))
        .willReturn(aResponse.withBody(slugInfo.applicationConfig))
      )
      stubFor(
        get(urlEqualTo(s"/hmrc/app-config-development/HEAD/$service.yaml"))
        .willReturn(aResponse.withBody("""
          |hmrc_config.a: 3
          |hmrc_config.b: 4
          |hmrc_config.list.2: 3
        """.stripMargin))
      )
      stubFor(
        get(urlEqualTo(s"/hmrc/app-config-qa/HEAD/$service.yaml"))
        .willReturn(aResponse.withBody("""
          |hmrc_config.a.b: 6
          |hmrc_config.d: 1
          |hmrc_config.d.e: 2
        """.stripMargin))
      )
      stubFor(get(urlEqualTo(s"/hmrc/app-config-staging/HEAD/$service.yaml")).willReturn(aResponse.withStatus(404)))
      stubFor(get(urlEqualTo(s"/hmrc/app-config-integration/HEAD/$service.yaml")).willReturn(aResponse.withStatus(404)))
      stubFor(get(urlEqualTo(s"/hmrc/app-config-externaltest/HEAD/$service.yaml")).willReturn(aResponse.withStatus(404)))
      stubFor(get(urlEqualTo(s"/hmrc/app-config-production/HEAD/$service.yaml")).willReturn(aResponse.withStatus(404)))

      val configByKey =
        for {
          _ <- slugInfoCollection
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
          r <- configService.configByKey(service)
        } yield r

      Json.toJson(configByKey.futureValue) shouldBe Json.parse("""
        { "a": {
            "local":        [{"source": "applicationConf", "value": "1"}                                                            ],
            "development":  [{"source": "applicationConf", "value": "1"}, {"source": "appConfigEnvironment", "value": "3"}          ],
            "qa":           [{"source": "applicationConf", "value": "1"}, {"source": "appConfigEnvironment", "value": "<<IGNORED>>"}],
            "integration":  [{"source": "baseConfig",      "value": "1"}                                                            ],
            "staging":      [{"source": "baseConfig",      "value": "1"}                                                            ],
            "externaltest": [{"source": "baseConfig",      "value": "1"}                                                            ],
            "production":   [{"source": "baseConfig",      "value": "1"}                                                            ]
          },
          "a.b": {
            "qa":           [                                             {"source": "appConfigEnvironment", "value": "6"}]
          },
          "b": {
            "local":        [{"source": "applicationConf", "value": "2"}                                                  ],
            "development":  [{"source": "applicationConf", "value": "2"}, {"source": "appConfigEnvironment", "value": "4"}],
            "qa":           [{"source": "applicationConf", "value": "2"}                                                  ],
            "integration":  [{"source": "baseConfig",      "value": "2"}                                                  ],
            "staging":      [{"source": "baseConfig",      "value": "2"}                                                  ],
            "externaltest": [{"source": "baseConfig",      "value": "2"}                                                  ],
            "production":   [{"source": "baseConfig",      "value": "2"}                                                  ]
          },
          "c": {
            "local":        [{"source": "applicationConf", "value": "1"}                                                            ],
            "development":  [{"source": "applicationConf", "value": "1"}, {"source": "appConfigEnvironment", "value": "3"          }],
            "qa":           [{"source": "applicationConf", "value": "1"}, {"source": "appConfigEnvironment", "value": "<<IGNORED>>"}],
            "integration":  [{"source": "baseConfig",      "value": "1"}                                                            ],
            "staging":      [{"source": "baseConfig",      "value": "1"}                                                            ],
            "externaltest": [{"source": "baseConfig",      "value": "1"}                                                            ],
            "production":   [{"source": "baseConfig",      "value": "1"}                                                            ]
          },
          "c.b": {
            "qa":           [                                             {"source": "appConfigEnvironment", "value": "6"}]
          },
          "d": {
            "qa":           [                                             {"source": "appConfigEnvironment", "value": "<<IGNORED>>"}]
          },
          "d.e": {
            "qa":           [                                             {"source": "appConfigEnvironment", "value": "2"}]
          },
          "list": {
            "local":        [{"source": "applicationConf", "value": "[1,2]"}                                                            ],
            "development":  [{"source": "applicationConf", "value": "[1,2]"}, {"source": "appConfigEnvironment", "value": "<<IGNORED>>"}],
            "qa":           [{"source": "applicationConf", "value": "[1,2]"}                                                            ],
            "integration":  [{"source": "baseConfig",      "value": "[1,2]"}                                                            ],
            "staging":      [{"source": "baseConfig",      "value": "[1,2]"}                                                            ],
            "externaltest": [{"source": "baseConfig",      "value": "[1,2]"}                                                            ],
            "production":   [{"source": "baseConfig",      "value": "[1,2]"}                                                            ]
          },
          "list.2": {
            "development": [                                                  {"source": "appConfigEnvironment", "value": "3" }]
          }
        }
      """")
    }
  }
}
