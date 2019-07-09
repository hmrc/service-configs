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

package uk.gov.hmrc.serviceconfigs.service

import java.util.Base64

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Compression, Sink, Source}
import akka.testkit.TestKit
import akka.util.ByteString
import org.scalatest.{FlatSpecLike, Matchers}
import uk.gov.hmrc.mongo.Awaiting

import scala.concurrent.ExecutionContext.Implicits.global

class SqsMessageHandlingSpec extends TestKit(ActorSystem("GzipCompressorSpec")) with FlatSpecLike with Matchers with Awaiting {

  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  "decompress" should "return a value that can be compressed back to the original input" in {

    val compressor = new SqsMessageHandling()

    val input = "test" * 64 * 1024 + "hello"  // Compression.gunzip default flush size

    val compressed = await(Source.single(ByteString.fromString(input))
      .via(Compression.gzip)
      .map(c => Base64.getEncoder.encodeToString(c.toArray))
      .runWith(Sink.head))

    val decompressed = await(compressor.decompress(compressed))

    decompressed should be (input)

  }
}
