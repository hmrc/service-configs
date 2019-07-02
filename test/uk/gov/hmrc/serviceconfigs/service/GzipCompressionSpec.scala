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

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import org.scalatest.{FlatSpecLike, Matchers}
import uk.gov.hmrc.mongo.Awaiting

class GzipCompressionSpec extends TestKit(ActorSystem("GzipCompressorSpec")) with FlatSpecLike with Matchers with Awaiting {

  private implicit val materializer: ActorMaterializer = ActorMaterializer()

  "decompress" should "return a value that can be compressed back to the original input" in {

    val compressor = new GzipCompression()

    val input = compress("hello world")

    val result = await(compressor.decompress(input))

    compress(result) should be (input)
  }

  private def compress(input: String) = {
    val inputBytes = input.getBytes("UTF-8")
    val bos = new ByteArrayOutputStream(inputBytes.length)
    val gzip = new GZIPOutputStream(bos)
    gzip.write(inputBytes)
    gzip.close()
    val compressed = bos.toByteArray
    bos.close()
    compressed
  }

}
