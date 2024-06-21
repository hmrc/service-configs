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

package uk.gov.hmrc.serviceconfigs.service

import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector
import uk.gov.hmrc.serviceconfigs.parser.InternalAuthConfigParser
import uk.gov.hmrc.serviceconfigs.persistence.InternalAuthConfigRepository

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class InternalAuthConfigService @Inject()(
  configAsCodeConnector       : ConfigAsCodeConnector
, internalAuthConfigRepository: InternalAuthConfigRepository
, parser                      : InternalAuthConfigParser
)(using ec: ExecutionContext
):

  def updateInternalAuth(): Future[Unit] =
    for
      zip <- configAsCodeConnector.streamInternalAuth()
      _   <- internalAuthConfigRepository.putAll(parser.parseZip(zip))
    yield ()
