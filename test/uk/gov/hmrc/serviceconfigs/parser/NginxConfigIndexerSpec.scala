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

import uk.gov.hmrc.serviceconfigs.model.Environment

class NginxConfigIndexerSpec
  extends AnyWordSpec
     with Matchers:

  "Indexer" should:
    "find the line number of the first path" in:
      val res = NginxConfigIndexer.index(config)
      res("/shutter/employees") shouldBe 1

    "find the index of the second path" in:
      val res = NginxConfigIndexer.index(config)
      res("/employees") shouldBe 7

    "find the line number of the /check-tax path" in:
      val res = NginxConfigIndexer.index(config)
      res("/check-tax") shouldBe 24

    "find the line number of an exact path" in:
      val res = NginxConfigIndexer.index(config)
      res("/some-path") shouldBe 47

    "not find a non-existent location" in:
      NginxConfigIndexer.index(config).get("/this/doesnt/exist") shouldBe None

    "index regex paths" in:
      val res = NginxConfigIndexer.index(config)
      res("^/gateway/((((infobip|nexmo)/(text|voice)/)?delivery)|(reports/count))") shouldBe 35

  "URL Generator" should:
    "return none if path is not indexed" in:
      val indexes = Map("/test123" -> 55, "/someurl" -> 4)
      NginxConfigIndexer.generateUrl(fileName, "HEAD", Environment.Production, "/nothing", indexes) shouldBe None

    "return a url to the correct file and line in github" in:
      val indexes = Map("/test123" -> 55, "/someurl" -> 4)
      NginxConfigIndexer.generateUrl(fileName, "HEAD", Environment.Production, "/test123", indexes) shouldBe Some("https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/production/frontend-proxy-application-rules.conf#L55")
      NginxConfigIndexer.generateUrl(fileName, "HEAD", Environment.Development, "/someurl", indexes) shouldBe Some("https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/development/frontend-proxy-application-rules.conf#L4")

    "return a url for a regex path" in:
      val res = NginxConfigIndexer.index(config)
      NginxConfigIndexer.generateUrl(fileName, "HEAD", Environment.Production, "^/gateway/((((infobip|nexmo)/(text|voice)/)?delivery)|(reports/count))", res) shouldBe Some("https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/production/frontend-proxy-application-rules.conf#L35")

    "use the fileName passed in the URL" in:
      val indexes = Map("/test123" -> 55)
      NginxConfigIndexer.generateUrl("anotherfile.conf", "HEAD", Environment.Production, "/test123", indexes) shouldBe Some("https://github.com/hmrc/mdtp-frontend-routes/blob/HEAD/production/anotherfile.conf#L55")

  val fileName = "frontend-proxy-application-rules.conf"

  val config =
    """location /shutter/employees {
      |  more_set_headers 'Cache-Control: no-cache, no-store, max-age=0, must-revalidate';
      |  more_set_headers 'Pragma: no-cache';
      |  more_set_headers 'Expires: 0';
      |}
      |
      |location /employees {
      |  if ( -f /etc/nginx/switches/mdtp/offswitch ) {
      |    return 503;
      |  }
      |  if ( -f /etc/nginx/switches/x/employees-frontend ) {
      |    error_page 503 /shutter/employees-frontend/index.html;
      |    return 503;
      |  }
      |  proxy_pass https://employees-frontend.local;
      |}
      |
      |location /shutter/bc-passengers-frontend {
      |  more_set_headers 'Cache-Control: no-cache, no-store, max-age=0, must-revalidate';
      |  more_set_headers 'Pragma: no-cache';
      |  more_set_headers 'Expires: 0';
      |}
      |
      |location /check-tax {
      |  if ( -f /etc/nginx/switches/x/offswitch ) {
      |    return 503;
      |  }
      |  if ( -f /etc/nginx/switches/x/passengers-frontend ) {
      |    error_page 503 /shutter/passengers-frontend/index.html;
      |    return 503;
      |  }
      |  proxy_pass https://passengers-frontend.local;
      |}
      |
      |location ~ ^/gateway/((((infobip|nexmo)/(text|voice)/)?delivery)|(reports/count)) {
      |
      |  if ( -f /etc/nginx/switches/x/offswitch )   {
      |    return 503;
      |  }
      |
      |  if ( -f /etc/nginx/switches/x/gateway )   {
      |    return 503;
      |  }
      |  proxy_pass https://gateway.public.local;
      |}
      |
      |location = /some-path {
      |  if ( -f /etc/nginx/switches/x/offswitch ) {
      |    return 503;
      |  }
      |  if ( -f /etc/nginx/switches/x/passengers-frontend ) {
      |    error_page 503 /shutter/passengers-frontend/index.html;
      |    return 503;
      |  }
      |  proxy_pass https://passengers-frontend.local;
      |}
      |"""
    .stripMargin
