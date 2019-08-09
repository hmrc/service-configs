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
import scala.collection.JavaConverters._

import javax.inject.{Inject, Singleton}
import play.api.{ConfigLoader, Configuration}

@Singleton
class NginxConfig @Inject()(configuration: Configuration) {

  def getKey[T](key: String)(implicit loader: ConfigLoader[T]): T =
    configuration
      .getOptional[T](key)
      .getOrElse(sys.error(s"$key not specified"))

  val configRepo: String = getKey[String]("nginx.config-repo")
  val frontendConfigFile: String = getKey[String]("nginx.config-file")

  val shutterConfig: NginxShutterConfig = {
    val ks = getKey[String]("nginx.shutter-killswitch-path")
    val ss = getKey[String]("nginx.shutter-serviceswitch-path-prefix")
    NginxShutterConfig(ks, ss)
  }

  val schedulerEnabled: Boolean = getKey[Boolean]("nginx.reload.enabled")
  val schedulerDelay: Long = getKey[Long]("nginx.reload.intervalminutes")

}

case class NginxShutterConfig(shutterKillswitchPath: String, shutterServiceSwitchPathPrefix: String)