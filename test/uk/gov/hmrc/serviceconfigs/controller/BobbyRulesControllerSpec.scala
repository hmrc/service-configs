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

package uk.gov.hmrc.serviceconfigs.controller

import org.apache.pekko.actor.ActorSystem
import org.mockito.Mockito.when
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.serviceconfigs.model.{BobbyRule, BobbyRules, Exemption}
import uk.gov.hmrc.serviceconfigs.service._
import play.api.libs.json._
import play.api.test.FakeRequest
import play.api.test.Helpers._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.time.LocalDate

class BobbyRulesControllerSpec
  extends AnyWordSpec
     with Matchers
     with MockitoSugar
     with ScalaFutures
     with IntegrationPatience:

  "processGithubWebhook" should:
    "accept unknown repository" in new Setup:
      when(mockBobbyRulesService.findAllRules())
        .thenReturn(Future.successful(
          BobbyRules(
            libraries = Seq(BobbyRule(
              organisation = "uk.gov.hmrc",
              name         = "name1",
              range        = "(,3.1.0)",
              reason       = "reason1",
              from         = LocalDate.parse("2015-11-01")
            )),
            plugins   = Seq(BobbyRule(
              organisation = "uk.gov.hmrc",
              name         = "name2",
              range        = "(,3.2.0)",
              reason       = "reason2",
              from         = LocalDate.parse("2015-11-02")
            ))
          )
        ))

      val response = call(
        controller.allRules,
        FakeRequest(GET, "/bobby/rules")
      )

      status(response) shouldBe 200
      contentAsJson(response) shouldBe Json.parse(
        s"""{
          "libraries": [ {
            "organisation"  : "uk.gov.hmrc",
            "name"          : "name1",
            "range"         : "(,3.1.0)",
            "reason"        : "reason1",
            "from"          : "2015-11-01",
            "exemptProjects" : []
          } ],
          "plugins": [ {
            "organisation"  : "uk.gov.hmrc",
            "name"          : "name2",
            "range"         : "(,3.2.0)",
            "reason"        : "reason2",
            "from"          : "2015-11-02",
            "exemptProjects" : []
          } ]
        }"""
      )

    "convert * to [0.0.0,) in range field to represent all versions" in new Setup:
      val json = Json.parse(
        s"""{
          "libraries": [ {
            "organisation"  : "uk.gov.hmrc",
            "name"          : "name1",
            "range"         : "*",
            "reason"        : "reason1",
            "from"          : "2015-11-01",
            "exemptProjects" : []
          } ],
          "plugins": [ {
            "organisation"  : "uk.gov.hmrc",
            "name"          : "name2",
            "range"         : "(,3.2.0)",
            "reason"        : "reason2",
            "from"          : "2015-11-02",
            "exemptProjects" : []
          } ]
        }"""
      )

      val expected = BobbyRules(
        libraries = Seq(BobbyRule(
          organisation = "uk.gov.hmrc",
          name = "name1",
          range = "[0.0.0,)",
          reason = "reason1",
          from = LocalDate.parse("2015-11-01")
        )),
        plugins = Seq(BobbyRule(
          organisation = "uk.gov.hmrc",
          name = "name2",
          range = "(,3.2.0)",
          reason = "reason2",
          from = LocalDate.parse("2015-11-02")
        ))
      )

      val result = Json.fromJson[BobbyRules](json)(BobbyRules.apiFormat)
      result shouldBe JsSuccess(expected)

    "deserialize legacy exemption format (strings) into Exemption objects for backward compatibility" in new Setup:
      val legacyJson = Json.parse(
        s"""{
          "libraries": [ {
            "organisation"  : "uk.gov.hmrc",
            "name"          : "test-lib",
            "range"         : "(,1.0.0)",
            "reason"        : "test reason",
            "from"          : "2026-01-01",
            "exemptProjects" : [ "service-one-frontend", "service-two-frontend" ]
          } ],
          "plugins": [ ]
        }"""
      )

      val result = Json.fromJson[BobbyRules](legacyJson)(BobbyRules.apiFormat)

      result shouldBe JsSuccess(
        BobbyRules(
          libraries = Seq(BobbyRule(
            organisation = "uk.gov.hmrc",
            name = "test-lib",
            range = "(,1.0.0)",
            reason = "test reason",
            from = LocalDate.parse("2026-01-01"),
            exemptProjects = Seq(
              Exemption("service-one-frontend", None),
              Exemption("service-two-frontend", None)
            )
          )),
          plugins = Seq.empty
        )
      )

    "deserialize new exemption format (objects with expiryDate) into Exemption objects" in new Setup:
      val newFormatJson = Json.parse(
        s"""{
          "libraries": [ {
            "organisation"  : "uk.gov.hmrc",
            "name"          : "test-lib",
            "range"         : "(,1.0.0)",
            "reason"        : "test reason",
            "from"          : "2026-01-01",
            "exemptProjects" : [ {
              "projectName" : "service-one-frontend",
              "expiryDate"  : "2027-01-31"
            }, {
              "projectName" : "service-two-frontend"
            } ]
          } ],
          "plugins": [ ]
        }"""
      )

      val result = Json.fromJson[BobbyRules](newFormatJson)(BobbyRules.apiFormat)

      result shouldBe JsSuccess(
        BobbyRules(
          libraries = Seq(BobbyRule(
            organisation = "uk.gov.hmrc",
            name = "test-lib",
            range = "(,1.0.0)",
            reason = "test reason",
            from = LocalDate.parse("2026-01-01"),
            exemptProjects = Seq(
              Exemption("service-one-frontend", Some(LocalDate.parse("2027-01-31"))),
              Exemption("service-two-frontend", None)
            )
          )),
          plugins = Seq.empty
        )
      )

  trait Setup:
    given ActorSystem = ActorSystem()

    val mockBobbyRulesService = mock[BobbyRulesService]

    val controller = new BobbyRulesController(
      bobbyRulesService = mockBobbyRulesService,
      mcc               = stubMessagesControllerComponents()
    )
