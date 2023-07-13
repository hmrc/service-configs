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
import play.api.{Logger, Logging}
import uk.gov.hmrc.serviceconfigs.model.InternalAuthConfig

import java.util.zip.ZipInputStream

class InternalAuthConfigParserSpec
  extends AnyWordSpec
  with Matchers with Logging {

  lazy val configZip = new ZipInputStream(this.getClass.getResource("/internal-auth-config-main.zip").openStream())
  lazy val parser = new InternalAuthConfigParser

  "An InternalAuthConfigParser" should {
    "parse a zip file " in {
      val result: Set[InternalAuthConfig] = parser.parseZip(configZip)

      logger.info(s"${result.mkString(" : ")}")

    }
  }

}
