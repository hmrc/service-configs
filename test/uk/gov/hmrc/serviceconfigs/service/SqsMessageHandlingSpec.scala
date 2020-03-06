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

package uk.gov.hmrc.serviceconfigs.service

import java.time.LocalDateTime
import java.util.Base64

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Compression, Flow, Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers
import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.serviceconfigs.model._

import scala.concurrent.ExecutionContext.Implicits.global

class SqsMessageHandlingSpec extends TestKit(ActorSystem("GzipCompressorSpec")) with AnyFlatSpecLike with Matchers with ScalaFutures {

  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  "decompress" should "return a value that can be compressed back to the original input" in {

    val compressor = new SqsMessageHandling()

    val aSlugInfo =
      SlugInfo(
        created         = LocalDateTime.of(2019, 6, 28, 11, 51,23),
        uri             = "https://store/slugs/my-slug/my-slug_0.27.0_0.5.2.tgz",
        name            = "my-slug",
        version         = Version.apply("0.27.0"),
        teams           = List.empty,
        runnerVersion   = "0.5.2",
        classpath       = "",
        jdkVersion      = "1.181.0",
        dependencies    = List(
          SlugDependency(
            path     = "lib1",
            version  = "1.2.0",
            group    = "com.test.group",
            artifact = "lib1"
          ),
          SlugDependency(
            path     = "lib2",
            version  = "0.66",
            group    = "com.test.group",
            artifact = "lib2")),
        applicationConfig = "",
        slugConfig        = "",
        latest            = true)

    val aDependencyConfig = DependencyConfig(
      group = "uk.gov.hmrc"
      , artefact = "time"
      , version = "3.2.0"
      , configs = Map(
        "includes.conf" -> "a = 1"
        , "reference.conf" -> ("test" * 64 * 1024 + "hello")
        // Compression.gunzip default flush size
      )
    )

    implicit val format: Writes[SlugMessage] = ApiSlugInfoFormats.slugFormat
    val input = Json.stringify(Json.toJson(SlugMessage(aSlugInfo, Seq(aDependencyConfig))))

    val compressed = (Source.single(input)
      .via(Flow.fromFunction(ByteString.fromString)
        .via(Compression.gzip)
        .fold(ByteString.empty)(_ ++ _)
        .map(b => Base64.getEncoder.encodeToString(b.toArray)))
      .runWith(Sink.head))

    val result = for {
      c <- compressed
      r <- compressor.decompress(c)
    }
    yield r

    whenReady(result){ r =>
        r should be (input)
    }
  }
}
