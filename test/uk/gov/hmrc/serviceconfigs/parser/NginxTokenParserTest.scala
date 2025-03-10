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

package uk.gov.hmrc.serviceconfigs.parser

import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.serviceconfigs.config.{NginxConfig, NginxShutterConfig}
import uk.gov.hmrc.serviceconfigs.model.NginxRouteConfig

class NginxTokenParserTest
  extends AnyWordSpec
     with Matchers
     with MockitoSugar:

  import Nginx._, NginxToken._

  val nginxConfig = mock[NginxConfig]
  val shutterConfig = NginxShutterConfig("killswitch", "serviceswitch")
  when(nginxConfig.shutterConfig).thenReturn(shutterConfig)
  val parser = NginxConfigParser(nginxConfig)
  import parser.NginxTokenParser.{ERROR_PAGE, LOCATION, NginxTokenReader, OTHER_PARAM, PROXY_PASS, RETURN, REWRITE, MARKER_COMMENT, COMMENT_LINE}

  "Parser" should:
    "find location blocks without prefixes" in:
      val tokens : Seq[NginxToken] = Seq(KEYWORD("location"), VALUE("/test"), OPEN_BRACKET(), KEYWORD("proxy_pass"), VALUE("http://www.com/123"), SEMICOLON(), CLOSE_BRACKET())
      parser.NginxTokenParser(tokens) shouldBe Right(List(NginxRouteConfig("/test", "http://www.com/123")))

    "parse return blocks with strings" in:
      val tokens = List(KEYWORD("return"), VALUE("404"), SEMICOLON())
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe RETURN(404, None)

    "parse location blocks with prefixes" in:
      val tokens : Seq[NginxToken] = Seq(KEYWORD("location"), VALUE("~"),  VALUE("/test/(test|dogs)"), OPEN_BRACKET(), KEYWORD("proxy_pass"), VALUE("http://www.com/123"), SEMICOLON(), CLOSE_BRACKET())
      parser.NginxTokenParser(tokens) shouldBe Right(List(NginxRouteConfig("/test/(test|dogs)", "http://www.com/123", isRegex = true)))

    "parse error_page parameters" in:
      val tokens : Seq[NginxToken] = Seq(KEYWORD("error_page"), VALUE("503"),  VALUE("/test"), SEMICOLON())
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe ERROR_PAGE(503, "/test")

    "parse proxy_pass parameters" in:
      val tokens: Seq[NginxToken] = Seq(KEYWORD("proxy_pass"), VALUE("http://www.com/test"), SEMICOLON())
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe PROXY_PASS("http://www.com/test")

    "parse return parameters" in:
      val tokens: Seq[NginxToken] = Seq(KEYWORD("return"), VALUE("503"), SEMICOLON())
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe RETURN(503, None)

    "parse return parameters with a url" in:
      val tokens: Seq[NginxToken] = Seq(KEYWORD("return"), VALUE("503"), VALUE("http://www.com/test"), SEMICOLON())
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe RETURN(503, Some("http://www.com/test"))

    "parse rewrite parameters" in:
      val tokens: Seq[NginxToken] = Seq(KEYWORD("rewrite"), VALUE("^"), VALUE("/business-account"), VALUE("last"), SEMICOLON())
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe REWRITE("^ /business-account last")

    "parse non-proxy_pass parameters" in:
      val tokens: Seq[NginxToken] = Seq(KEYWORD("setheader"), VALUE("'some quoted values'"), SEMICOLON())
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe OTHER_PARAM("setheader", "'some quoted values'")

    "parse arbitrary comments" in:
      val tokens: Seq[NginxToken] = Seq(COMMENT("# this is a comment"))
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe COMMENT_LINE()

    "parse the special #!NOT_SHUTTERABLE comment" in:
      val tokens: Seq[NginxToken] = Seq(COMMENT("#!NOT_SHUTTERABLE"))
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe MARKER_COMMENT("NOT_SHUTTERABLE")

    "parse the special #!NON_STANDARD_SHUTTERING comment" in:
      val tokens: Seq[NginxToken] = Seq(COMMENT("#!NON_STANDARD_SHUTTERING"))
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe MARKER_COMMENT("NON_STANDARD_SHUTTERING")

    "disregard anything after a marker comment" in:
      val tokens: Seq[NginxToken] = Seq(COMMENT("#!NOT_SHUTTERABLE some other text"))
      val reader = NginxTokenReader(tokens)
      parser.NginxTokenParser.parameter(reader).get shouldBe MARKER_COMMENT("NOT_SHUTTERABLE")

  "locToRoute" should:
    "set the regex flag is the location contains a regex value" in:
      val location = LOCATION(path = "/test/a.+", body = List(PROXY_PASS("http://www.com")), regex = true)
      parser.NginxTokenParser.locToRoute(location) shouldBe Some(NginxRouteConfig("/test/a.+", "http://www.com", isRegex = true))

    "not set the regex flag on non-regex routes" in:
      val location = LOCATION(path = "/test/a",  body = List(PROXY_PASS("http://www.com")))
      parser.NginxTokenParser.locToRoute(location) shouldBe Some(NginxRouteConfig("/test/a", "http://www.com", isRegex = false))
