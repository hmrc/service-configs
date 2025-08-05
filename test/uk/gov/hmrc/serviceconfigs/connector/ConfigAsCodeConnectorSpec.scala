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

package uk.gov.hmrc.serviceconfigs.connector

import org.apache.pekko.actor.ActorSystem
import com.github.tomakehurst.wiremock.client.WireMock._
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import play.api.libs.json.Json
import uk.gov.hmrc.http.test.{HttpClientV2Support, WireMockSupport}
import uk.gov.hmrc.serviceconfigs.config.GithubConfig
import uk.gov.hmrc.serviceconfigs.model.{CommitId, RepoName, Version}

import scala.concurrent.ExecutionContext.Implicits.global
import java.util.Base64

class ConfigAsCodeConnectorSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with HttpClientV2Support:

  private val token = "TOKEN"

  private given ActorSystem = ActorSystem()

  private val githubConfig = GithubConfig(Configuration(
    "github.open.api.apiurl" -> s"$wireMockUrl/api",
    "github.open.api.rawurl" -> s"$wireMockUrl/raw",
    "github.open.api.token"  -> token
  ))

  private val connector = ConfigAsCodeConnector(githubConfig, httpClientV2)

  "ConfigAsCode.streamGithub" should:
    "return stream" in:
      stubFor(
        get(urlEqualTo(s"/api/repos/hmrc/app-config-common/zipball/HEAD"))
          .willReturn(aResponse().withBodyFile("test.zip"))
      )

      val is = connector.streamGithub(RepoName("app-config-common")).futureValue
      // convert to Map(name -> content)
      Iterator
        .continually(is.getNextEntry)
        .takeWhile(_ != null)
        .foldLeft(Map.empty[String, String]){ (acc, entry) =>
          val name    = entry.getName.drop(entry.getName.indexOf('/') + 1)
          val content = scala.io.Source.fromInputStream(is).mkString
          acc + (name -> content)
        } shouldBe Map("test.txt" -> "TEST\n")

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/api/repos/hmrc/app-config-common/zipball/HEAD"))
          .withHeader("Authorization", equalTo(s"token $token"))
      )

  "ConfigAsCode.getLatestCommitId" should:
    "return commitId" in:
      stubFor(
        get(urlEqualTo(s"/api/repos/hmrc/app-config-common/commits/HEAD"))
          .willReturn(aResponse().withBody("27a469be69c988822b2bda722833b24301afb691"))
      )

      connector.getLatestCommitId(RepoName("app-config-common")).futureValue shouldBe CommitId("27a469be69c988822b2bda722833b24301afb691")

      wireMockServer.verify(
        getRequestedFor(urlPathEqualTo("/api/repos/hmrc/app-config-common/commits/HEAD"))
          .withHeader("Authorization", equalTo(s"token $token"))
          .withHeader("Accept"       , equalTo(s"application/vnd.github.sha"))
      )

  "ConfigAsCode.getVersionedFileContent" should:
    "return file content as string" in:
      val expected =
        """# Add all the application routes to the app.routes file
        |->         /internal-auth-admin-frontend  app.Routes
        |->         /                              health.Routes
        |""".stripMargin

      val expectedBody = String(Base64.getMimeEncoder().encode(expected.getBytes()))

      stubFor(
        get(urlEqualTo(s"/api/repos/hmrc/test-repo/contents/conf/prod.routes?ref=v0.0.1"))
          .willReturn(
            aResponse()
              .withBody(Json.obj("content" -> expectedBody).toString)
          )
      )

      connector.getVersionedFileContent(RepoName("test-repo"), "conf/prod.routes", Version(0, 0, 1)).futureValue shouldBe Some(expected)
