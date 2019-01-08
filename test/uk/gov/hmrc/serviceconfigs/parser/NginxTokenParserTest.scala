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

import org.scalatest.{FlatSpec, Matchers}
import uk.gov.hmrc.serviceconfigs.parser.NginxTokenParser.{OTHER_PARAM, PROXY_PASS}
class NginxTokenParserTest extends FlatSpec with Matchers{

  import Nginx._

  "Parser" should "find location blocks without prefixes" in {
    val tokens : Seq[NginxToken] = Seq(KEYWORD("location"), VALUE("/test"), OPEN_BRACKET(), KEYWORD("proxy_pass"), VALUE("http://www.com/123"), SEMICOLON(), CLOSE_BRACKET())
    NginxTokenParser(tokens) shouldBe List(ParserFrontendRoute("/test", "http://www.com/123"))
  }

  it should "parse location blocks with prefixes" in {
    val tokens : Seq[NginxToken] = Seq(KEYWORD("location"), VALUE("~"),  VALUE("/test/(test|dogs)"), OPEN_BRACKET(), KEYWORD("proxy_pass"), VALUE("http://www.com/123"), SEMICOLON(), CLOSE_BRACKET())
    NginxTokenParser(tokens) shouldBe List(ParserFrontendRoute("/test/(test|dogs)", "http://www.com/123"))
  }

  it should "parse proxy_pass parameters" in {
    val tokens: Seq[NginxToken] = Seq(KEYWORD("proxy_pass"), VALUE("http://www.com/test"), SEMICOLON())
    val reader = new NginxTokenParser.NginxTokenReader(tokens)
    NginxTokenParser.parameter(reader).get shouldBe PROXY_PASS("http://www.com/test")
  }

  it should "parse non-proxy_pass parameters" in {
    val tokens: Seq[NginxToken] = Seq(KEYWORD("setheader"), VALUE("'some quoted values'"), SEMICOLON())
    val reader = new NginxTokenParser.NginxTokenReader(tokens)
    NginxTokenParser.parameter(reader).get shouldBe OTHER_PARAM("setheader", "'some quoted values'")
  }

}
