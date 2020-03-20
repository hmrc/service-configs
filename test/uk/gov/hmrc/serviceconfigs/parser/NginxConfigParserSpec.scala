/*
 * Copyright 2020 HM Revenue & Customs
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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.mockito.MockitoSugar
import uk.gov.hmrc.serviceconfigs.config.{NginxConfig, NginxShutterConfig}
import uk.gov.hmrc.serviceconfigs.model.{FrontendRoute, ShutterSwitch}

class NginxConfigParserSpec extends AnyFlatSpec with Matchers with MockitoSugar {

  val nginxConfig = mock[NginxConfig]
  val shutterConfig = NginxShutterConfig("/etc/nginx/switches/mdtp/offswitch", " /etc/nginx/switches/mdtp/")
  when(nginxConfig.shutterConfig).thenReturn(shutterConfig)

  "NginxConfigParser" should "parse regex routes" in {

    val configRegex =
      """location ~ ^/test-gateway/((((infobip|nexmo)/(text|voice)/)?delivery-details)|(reports/count)) {
        |
        |  if ( -f /etc/nginx/switches/x/offswitch )   {
        |    return 503;
        |  }
        |
        |  if ( -f /etc/nginx/switches/x/test-gateway )   {
        |    return 503;
        |  }
        |  proxy_pass https://test-gateway.public.local;
        |}""".stripMargin

    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(configRegex)
    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("^/test-gateway/((((infobip|nexmo)/(text|voice)/)?delivery-details)|(reports/count))", "https://test-gateway.public.local", isRegex = true)
  }


  it should "parse normal routes" in {
    val configNormal =
      """location /mandate {
        |
        |  if ( -f /etc/nginx/switches/mdtp/offswitch )   {
        |    return 503;
        |  }
        |
        |  if ( -f /etc/nginx/switches/mdtp/test-client-mandate-frontend )   {
        |    error_page 503 /shutter/mandate/index.html;
        |    return 503;
        |  }
        |  proxy_pass https://test-frontend.public.local;
        |}
      """.stripMargin

    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(configNormal)
    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("/mandate", "https://test-frontend.public.local", shutterKillswitch = Some(ShutterSwitch("/etc/nginx/switches/mdtp/offswitch", Some(503))),
      shutterServiceSwitch = Some(ShutterSwitch("/etc/nginx/switches/mdtp/test-client-mandate-frontend", Some(503), Some("/shutter/mandate/index.html"), None )))
  }


  it should "drop routes without a proxy_pass" in {
    val config =
      """
        |location /users/dp-settings.js {
        |  more_set_headers 'Cache-Control: public';
        |  expires 3600;
        |  return 204;
        |}""".stripMargin

    val parsed = new NginxConfigParser(nginxConfig).parseConfig(config)

    parsed shouldBe Right(Nil)
  }

  it should "parse s3 proxy_pass routes" in {

    val config =
      """
        |location /assets {
        |  more_set_headers 'Cache-Control: public';
        |  expires 3600;
        |  more_set_headers 'X-Frame-Options: DENY';
        |  more_set_headers 'X-XSS-Protection: 1; mode=block';
        |  more_set_headers 'X-Content-Type-Options: nosniff';
        |  proxy_pass $s3_upstream;
        |}""".stripMargin
    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(config)

    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("/assets", "$s3_upstream")
  }

  it should "parse routes with set header params" in {
    val config =
      """location /lol {
        |  proxy_set_header Host lol-frontend.public.local;
        |  proxy_pass https://lol-frontend.public.local;
        |}""".stripMargin

    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(config)

    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("/lol", "https://lol-frontend.public.local")
  }

  it should "parse a shutter killswitch" in {
    val config =
      """location /lol {
        |  if ( -f /etc/nginx/switches/mdtp/offswitch )   {
        |    return 503;
        |  }
        |  proxy_pass https://lol-frontend.public.local;
        |}""".stripMargin

    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(config)

    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("/lol", "https://lol-frontend.public.local", shutterKillswitch = Some(ShutterSwitch("/etc/nginx/switches/mdtp/offswitch", Some(503))), shutterServiceSwitch = None)
  }

  it should "parse a shutter killswitch with an error page" in {
    val config =
      """location /lol {
        |  if ( -f /etc/nginx/switches/mdtp/offswitch )   {
        |    error_page 503 /shutter/index.html;
        |    return 503;
        |  }
        |  proxy_pass https://lol-frontend.public.local;
        |}""".stripMargin

    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(config)

    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("/lol", "https://lol-frontend.public.local",
      shutterKillswitch = Some(ShutterSwitch("/etc/nginx/switches/mdtp/offswitch", Some(503), errorPage = Some("/shutter/index.html"))), shutterServiceSwitch = None)
  }

  it should "parse a shutter service switch" in {
    val config =
      """location /lol {
        |  if ( -f /etc/nginx/switches/mdtp/test-client-mandate-frontend )   {
        |    error_page 503 /shutter/mandate/index.html;
        |    return 503;
        |  }
        |  proxy_pass https://lol-frontend.public.local;
        |}""".stripMargin

    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(config)

    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("/lol", "https://lol-frontend.public.local",
      shutterServiceSwitch = Some(ShutterSwitch("/etc/nginx/switches/mdtp/test-client-mandate-frontend", Some(503), Some("/shutter/mandate/index.html"), None)),
      shutterKillswitch = None
    )
  }

  it should "parse a marker comment" in {
    val config =
      """location /lol {
        |  #!NOT_SHUTTERABLE
        |  proxy_pass https://lol-frontend.public.local;
        |}""".stripMargin

    val eCfg = new NginxConfigParser(nginxConfig).parseConfig(config)

    eCfg.isRight shouldBe true
    val Right(cfg) = eCfg
    cfg.head shouldBe FrontendRoute("/lol", "https://lol-frontend.public.local",
      markerComments = Set("NOT_SHUTTERABLE")
    )
  }

}
