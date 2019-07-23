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

package uk.gov.hmrc.serviceconfigs.config

import javax.inject.{Inject, Singleton}
import play.api.Configuration

@Singleton
class NginxConfig @Inject()(configuration: Configuration) {

  val configRepo: String                      = configuration.getOptional[String](s"nginx.config-repo").getOrElse("mdtp-frontend-routes")
  val frontendConfigFile: String              = configuration.getOptional[String](s"nginx.config-file").getOrElse("frontend-proxy-application-rules.conf")

  val shutterConfig = {
    val ks = configuration.getOptional[String]("nginx.shutter-killswitch-path").getOrElse("/etc/nginx/switches/mdtp/offswitch")
    val ss = configuration.getOptional[String]("nginx.shutter-serviceswitch-path-prefix").getOrElse("/etc/nginx/switches/mdtp/")
    NginxShutterConfig(ks, ss)
  }

  val schedulerEnabled: Boolean               = configuration.getOptional[Boolean](s"nginx.reload.enabled").getOrElse(false)
  val schedulerDelay: Long                    = configuration.getOptional[Long](s"nginx.reload.intervalminutes").getOrElse(20)

}

case class NginxShutterConfig(shutterKillswitchPath: String, shutterServiceSwitchPathPrefix: String)