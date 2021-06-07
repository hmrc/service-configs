/*
 * Copyright 2021 HM Revenue & Customs
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

import akka.stream.Materializer
import akka.stream.scaladsl.{Compression, Sink, Source}
import akka.util.ByteString
import javax.inject.{Inject, Singleton}

import scala.concurrent.Future

@Singleton
class SqsMessageHandling @Inject()(implicit materializer: Materializer) {

  private lazy val decoder = Base64.getDecoder

  def decompress(message: String): Future[String] =
    decompress(decoder.decode(message))

  private def decompress(compressedInput: Array[Byte]): Future[String] =
    Source.single(ByteString.fromArray(compressedInput))
      .via(Compression.gunzip())
      .fold(ByteString.empty)(_ ++ _)
      .map(_.utf8String)
      .runWith(Sink.head)

}
