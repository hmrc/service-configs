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

class NginxConfigIndexerSpec extends FlatSpec with Matchers {

  "Indexer" should "find the line number of the first path" in {
    val res = NginxConfigIndexer.index(config)
    res("/shutter/employee-expenses") shouldBe 1
  }

  it should "find the index of the second path" in {
    val res = NginxConfigIndexer.index(config)
    res("/employee-expenses") shouldBe 7
  }

  it should "find the line number of the last path" in {
    val res = NginxConfigIndexer.index(config)
    res("/check-tax-on-goods-you-bring-into-the-uk") shouldBe 24
  }

  it should "not find a non-existent location" in {
    NginxConfigIndexer.index(config).get("/this/doesnt/exist") shouldBe None
  }




  "URL Generator" should "return none if path is not indexed" in {
    val indexes = Map("/test123" -> 55, "/someurl" -> 4)
    NginxConfigIndexer.generateUrl("prod", "/nothing", indexes) shouldBe None
  }

  it should "return a url to the correct file and line in github" in {
    val indexes = Map("/test123" -> 55, "/someurl" -> 4)
    NginxConfigIndexer.generateUrl("production", "/test123", indexes) shouldBe Some("https://github.com/hmrc/mdtp-frontend-routes/blob/master/production/frontend-proxy-application-rules.conf#L55")
    NginxConfigIndexer.generateUrl("development", "/someurl", indexes) shouldBe Some("https://github.com/hmrc/mdtp-frontend-routes/blob/master/development/frontend-proxy-application-rules.conf#L4")
  }




  val config =
    """location /shutter/employee-expenses {
      |  more_set_headers 'Cache-Control: no-cache, no-store, max-age=0, must-revalidate';
      |  more_set_headers 'Pragma: no-cache';
      |  more_set_headers 'Expires: 0';
      |}
      |
      |location /employee-expenses {
      |  if ( -f /etc/nginx/switches/mdtp/offswitch ) {
      |    return 503;
      |  }
      |  if ( -f /etc/nginx/switches/mdtp/employee-expenses-frontend ) {
      |    error_page 503 /shutter/employee-expenses-frontend/index.html;
      |    return 503;
      |  }
      |  proxy_pass https://employee-expenses-frontend.local;
      |}
      |
      |location /shutter/bc-passengers-frontend {
      |  more_set_headers 'Cache-Control: no-cache, no-store, max-age=0, must-revalidate';
      |  more_set_headers 'Pragma: no-cache';
      |  more_set_headers 'Expires: 0';
      |}
      |
      |location /check-tax-on-goods-you-bring-into-the-uk {
      |  if ( -f /etc/nginx/switches/mdtp/offswitch ) {
      |    return 503;
      |  }
      |  if ( -f /etc/nginx/switches/mdtp/bc-passengers-frontend ) {
      |    error_page 503 /shutter/bc-passengers-frontend/index.html;
      |    return 503;
      |  }
      |  proxy_pass https://bc-passengers-frontend.local;
      |}"""
    .stripMargin
}
