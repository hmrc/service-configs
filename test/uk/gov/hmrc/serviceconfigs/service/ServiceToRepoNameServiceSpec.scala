/*
 * Copyright 2024 HM Revenue & Customs
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

package uk.gov.hmrc.serviceconfigs.service

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.zip.ZipInputStream

class ServiceToRepoNameServiceSpec extends AnyWordSpec with Matchers with OptionValues:
  import ServiceToRepoNameService._

  "extractServiceToRepoNames" should:
    "return mapping of repo names and slug name when they are different" in:

      val zip = ZipInputStream(this.getClass.getResource("/job-builders.zip").openStream())

      extractServiceToRepoNames(zip) shouldBe
        List(
          ("service-two", "service-two-slug"),
          ("service-four", "service-four-slug"),
          ("service-six", "service-six-slug"),
          ("service-eight", "service-eight-slug")
        )

  "MicroserviceMatch" should:
    "extract the repo name from MvnMicroserviceJobBuilder constructor" in:
      Seq(
        "new MvnMicroserviceJobBuilder(FOLDER, 'service-one', JavaType.OPENJDK_JRE8)",
        "new MvnMicroserviceJobBuilder(FOLDER, \"service-one\", JavaType.OPENJDK_JRE8)",
        "new MvnMicroserviceJobBuilder(FOLDER, 'service-one')"
      ).foreach:
        case MicroserviceMatch("service-one") =>
        case line => fail(s"Failed to extract service name from: $line")

    "return None when no match is found" in:
      MicroserviceMatch.findFirstMatchIn("new MvnMicroserviceJobBuilder(FOLDER)") shouldBe None

  "AemMatch" should:
    "extract the repo name from MvnAemJobBuilder constructor" in:
      Seq(
        "new MvnAemJobBuilder(TEAM,'service-one','service-one-slug')",
        "new MvnAemJobBuilder(TEAM, \"service-one\",'service-one-slug')"
      ).foreach:
        case AemMatch("service-one") =>
        case line => fail(s"Failed to extract service name from: $line")

    "return None when no match is found" in:
      AemMatch.findFirstMatchIn("new MvnAemJobBuilder(TEAM)") shouldBe None

  "UploadSlugMatch" should:
    "extract the artefact name from andUploadSlug" in:
      Seq(
        ".andUploadSlug('artifact.tgz','service-one','service-one-upload-slug')",
        ".andUploadSlug('artifact.tgz','service-one',\"service-one-upload-slug\")"
      ).foreach:
        case UploadSlugMatch("service-one-upload-slug") =>
        case line => fail(s"Failed to extract artefact name from: $line")

    "return None when no match is found" in:
      UploadSlugMatch.findFirstMatchIn(".andUploadSlug('artifact.tgz')") shouldBe None
