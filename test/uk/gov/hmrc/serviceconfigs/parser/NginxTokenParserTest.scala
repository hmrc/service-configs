/*
 * Copyright 2019 HM Revenue & Customs
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
import org.scalatest.{FlatSpec, Matchers}
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.serviceconfigs.config.{NginxConfig, NginxShutterConfig}
import uk.gov.hmrc.serviceconfigs.model.FrontendRoute
class NginxTokenParserTest extends FlatSpec with Matchers with MockitoSugar{

  import Nginx._

  val nginxConfig = mock[NginxConfig]
  val shutterConfig = NginxShutterConfig("killswitch", "serviceswitch")
  when(nginxConfig.shutterConfig).thenReturn(shutterConfig)
  val parser = new NginxConfigParser(nginxConfig)
  import parser.NginxTokenParser.{ERROR_PAGE, LOCATION, NginxTokenReader, OTHER_PARAM, PROXY_PASS, RETURN}

  "Parser" should "find location blocks without prefixes" in {
    val tokens : Seq[NginxToken] = Seq(KEYWORD("location"), VALUE("/test"), OPEN_BRACKET(), KEYWORD("proxy_pass"), VALUE("http://www.com/123"), SEMICOLON(), CLOSE_BRACKET())
    parser.NginxTokenParser(tokens) shouldBe Right(List(FrontendRoute("/test", "http://www.com/123")))
  }

  it should "parse return blocks with strings" in {
    val tokens = List(KEYWORD("return"), VALUE("404"), SEMICOLON())
    val reader = new NginxTokenReader(tokens)
    parser.NginxTokenParser.parameter(reader).get shouldBe RETURN(404)
  }

  it should "parse location blocks with prefixes" in {
    val tokens : Seq[NginxToken] = Seq(KEYWORD("location"), VALUE("~"),  VALUE("/test/(test|dogs)"), OPEN_BRACKET(), KEYWORD("proxy_pass"), VALUE("http://www.com/123"), SEMICOLON(), CLOSE_BRACKET())
    parser.NginxTokenParser(tokens) shouldBe Right(List(FrontendRoute("/test/(test|dogs)", "http://www.com/123", isRegex = true)))
  }

  it should "parse error_page parameters" in {
    val tokens : Seq[NginxToken] = Seq(KEYWORD("error_page"), VALUE("503"),  VALUE("/test"), SEMICOLON())
    val reader = new NginxTokenReader(tokens)
    parser.NginxTokenParser.parameter(reader).get shouldBe ERROR_PAGE(503, "/test")
  }

  it should "parse proxy_pass parameters" in {
    val tokens: Seq[NginxToken] = Seq(KEYWORD("proxy_pass"), VALUE("http://www.com/test"), SEMICOLON())
    val reader = new NginxTokenReader(tokens)
    parser.NginxTokenParser.parameter(reader).get shouldBe PROXY_PASS("http://www.com/test")
  }

  it should "parse non-proxy_pass parameters" in {
    val tokens: Seq[NginxToken] = Seq(KEYWORD("setheader"), VALUE("'some quoted values'"), SEMICOLON())
    val reader = new NginxTokenReader(tokens)
    parser.NginxTokenParser.parameter(reader).get shouldBe OTHER_PARAM("setheader", "'some quoted values'")
  }

  "locToRoute" should "set the regex flag is the location contains a regex value" in {
    val location = LOCATION(path = "/test/a.+", body = List(PROXY_PASS("http://www.com")), regex = true)
    parser.NginxTokenParser.locToRoute(location) shouldBe Some(FrontendRoute("/test/a.+", "http://www.com", isRegex = true))
  }

  it should "not set the regex flag on non-regex routes" in {
    val location = LOCATION(path = "/test/a",  body = List(PROXY_PASS("http://www.com")))
    parser.NginxTokenParser.locToRoute(location) shouldBe Some(FrontendRoute("/test/a", "http://www.com", isRegex = false))
  }

}
