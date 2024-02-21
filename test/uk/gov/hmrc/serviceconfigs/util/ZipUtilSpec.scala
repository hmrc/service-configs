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

package uk.gov.hmrc.serviceconfigs.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import java.util.zip.ZipInputStream

class ZipUtilSpec extends AnyWordSpec with Matchers {

  "findServiceToRepoNames" should {
    "return mapping of repo names and slug name when they are different" in {

      val zip = new ZipInputStream(this.getClass.getResource("/job-builders.zip").openStream())

      ZipUtil.findServiceToRepoNames(zip, """jobs/live/(.*).groovy""".r) shouldBe
        List(
          ("service-two", "service-two-slug"),
          ("service-four", "service-four-slug"),
          ("service-six", "service-six-slug"),
          ("service-eight", "service-eight-slug")
        )
    }
  }

  "BuildJobPattern.microserviceMatch" should {
    "extract the repo name from the 2nd param when a 3rd param also exists" in {
      BuildJobPattern.microserviceMatch("new MvnMicroserviceJobBuilder(FOLDER, 'service-one', JavaType.OPENJDK_JRE8)") shouldBe Some("service-one")
    }

    "extract the repo name from the 2nd param and be quote agnostic" in {
      BuildJobPattern.microserviceMatch("new MvnMicroserviceJobBuilder(FOLDER, \"service-one\", JavaType.OPENJDK_JRE8)") shouldBe Some("service-one")
    }

    "extract the repo name from the 2nd param" in {
      BuildJobPattern.microserviceMatch("new MvnMicroserviceJobBuilder(FOLDER, 'service-one')") shouldBe Some("service-one")
    }

    "return None when no match is found" in {
      BuildJobPattern.microserviceMatch("new MvnMicroserviceJobBuilder(FOLDER)") shouldBe None
    }
  }

  "BuildJobsPattern.aemMatch" should {
    "extract the repo name from the 2nd param" in {
      BuildJobPattern.aemMatch("new MvnAemJobBuilder(TEAM,'service-one','service-one-slug')") shouldBe Some("service-one")
    }

    "extract the repo name from the 2nd param and be quote agnostic" in {
      BuildJobPattern.aemMatch("new MvnAemJobBuilder(TEAM, \"service-one\",'service-one-slug')") shouldBe Some("service-one")
    }

    "return None when no match is found" in {
      BuildJobPattern.aemMatch("new MvnAemJobBuilder(TEAM)") shouldBe None
    }
  }

  "BuildJobsPattern.uploadSlugMatch" should {
    "extract the upload slug name from the 3rd parameter" in {
      BuildJobPattern.uploadSlugMatch(".andUploadSlug('artifact.tgz','service-one','service-one-upload-slug')") shouldBe Some("service-one-upload-slug")
    }

    "extract the upload slug name from the 3rd parameter and be quote agnostic" in {
      BuildJobPattern.uploadSlugMatch(".andUploadSlug('artifact.tgz','service-one',\"service-one-upload-slug\")") shouldBe Some("service-one-upload-slug")
    }

    "return None when no match is found" in {
      BuildJobPattern.uploadSlugMatch(".andUploadSlug('artifact.tgz')")
    }
  }
}
