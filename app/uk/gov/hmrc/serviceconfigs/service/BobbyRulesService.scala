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

import javax.inject.{Inject, Singleton}
import play.api.Logger
import uk.gov.hmrc.serviceconfigs.connector.BobbyRulesConnector
import uk.gov.hmrc.serviceconfigs.persistence.BobbyRulesRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BobbyRulesService @Inject()(
  bobbyRulesRepository: BobbyRulesRepository,
  bobbyRulesConnector : BobbyRulesConnector,
)(implicit
  ec : ExecutionContext
) {
  private val logger = Logger(this.getClass)

  // println(s">>>>>>>>>>>>>>>>>>>>HERE")
  // update().map { _ => println(s"<<<<<<<<<<<<<<<<<<HERE") }.recover { case t => println(s"FAILED: ${t.getMessage}"); throw t }

  def update(): Future[Unit] =
    for {
      _      <- Future.successful(logger.info("Starting"))
      config <- bobbyRulesConnector.findAllRules()
      _      <- bobbyRulesRepository.putAll(config)
    } yield ()

  def findAllRules(): Future[String] =
    bobbyRulesRepository.findAll()
}
