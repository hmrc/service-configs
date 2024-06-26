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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NginxLexerSpec
  extends AnyWordSpec
     with Matchers:

  import Nginx._, NginxToken._

  "NginxLexer" should:
    "tokenize openBracket" in:
      NginxLexer.parse(NginxLexer.openBracket, "{").get shouldBe OPEN_BRACKET()

    "tokenize closeBracket" in:
      NginxLexer.parse(NginxLexer.closeBracket, "}").get shouldBe CLOSE_BRACKET()

    "tokenize semicolon" in:
      NginxLexer.parse(NginxLexer.semicolon, ";").get shouldBe SEMICOLON()

    "tokenize comments" in:
      NginxLexer.parse(NginxLexer.comment, "# this is a comment").get shouldBe COMMENT("# this is a comment")

    "tokenize keywords" in:
      NginxLexer.parse(NginxLexer.keyword, "proxy_pass ").get shouldBe KEYWORD("proxy_pass")

    "tokenize values" in:
      NginxLexer.parse(NginxLexer.value, "abcd").get shouldBe VALUE("abcd")
      NginxLexer.parse(NginxLexer.value, "'( this is -true )'").get shouldBe VALUE("'( this is -true )'")
      NginxLexer.parse(NginxLexer.value, "http://www.url.com").get shouldBe VALUE("http://www.url.com")

    "tokenize quoted values" in:
      NginxLexer.parse(NginxLexer.value, "'test 1234'").get shouldBe VALUE("'test 1234'")
      NginxLexer.parse(NginxLexer.value, "\"test 1234\"").get shouldBe VALUE("\"test 1234\"")

    "tokenize a location" in:
      val loc = "location ~ ^/dog-frontend/(a|b)/)?details)|(reports)) {"
      val res = NginxLexer.parse(NginxLexer.tokens, loc).get
      res shouldBe Seq(KEYWORD("location"), VALUE("~"), VALUE("^/dog-frontend/(a|b)/)?details)|(reports))"), OPEN_BRACKET())

    "tokenize an if block" in:
      val ifb =  """if ( -f /etc/nginx/switches/mdtp/test-client-mandate-frontend )   {""".stripMargin
      val res = NginxLexer.parse(NginxLexer.tokens, ifb).get
      res shouldBe Seq(KEYWORD("if"), VALUE("("), VALUE("-f"), VALUE("/etc/nginx/switches/mdtp/test-client-mandate-frontend"), VALUE(")"), OPEN_BRACKET())

    "tokenize a parameter" in:
      val input = "error_page 503 /shutter/mandate/index.html;"
      val res = NginxLexer.parse(NginxLexer.tokens, input).get
      res shouldBe Seq(KEYWORD("error_page"), VALUE("503"), VALUE("/shutter/mandate/index.html"), SEMICOLON())

    "tokenize return with code and message" in:
      val input = """return 404 "Invalid request route";"""
      val res = NginxLexer.parse(NginxLexer.tokens, input).get
      res shouldBe Seq(KEYWORD("return"), VALUE("404"), VALUE("\"Invalid request route\""), SEMICOLON())
