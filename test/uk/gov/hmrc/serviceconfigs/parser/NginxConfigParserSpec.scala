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
import Nginx._

class NginxConfigParserSpec extends FlatSpec with Matchers{

  "NginxLexer" should "tokenize openBracket" in {
    NginxLexer.parse(NginxLexer.openBracket, "{").get shouldBe OPEN_BRACKET()
  }

  it should "tokenize closeBracket" in {
    NginxLexer.parse(NginxLexer.closeBracket, "}").get shouldBe CLOSE_BRACKET()
  }

  it should "tokenize semicolon" in {
    NginxLexer.parse(NginxLexer.semicolon, ";").get shouldBe SEMICOLON()
  }

  it should "tokenize comments" in {
    NginxLexer.parse(NginxLexer.comment, "# this is a comment").get shouldBe COMMENT()
  }

  it should "tokenize keywords" in {
    NginxLexer.parse(NginxLexer.keyword, "proxy_pass ").get shouldBe KEYWORD("proxy_pass")
  }

  it should "tokenize values" in {
    NginxLexer.parse(NginxLexer.value, "abcd").get shouldBe VALUE("abcd")
    NginxLexer.parse(NginxLexer.value, "'( this is -true )'").get shouldBe VALUE("'( this is -true )'")
    NginxLexer.parse(NginxLexer.value, "http://www.url.com").get shouldBe VALUE("http://www.url.com")
  }

}
