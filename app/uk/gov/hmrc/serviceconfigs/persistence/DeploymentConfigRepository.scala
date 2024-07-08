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

package uk.gov.hmrc.serviceconfigs.persistence

import org.mongodb.scala.{ObservableFuture, SingleObservableFuture}
import com.mongodb.client.model.Indexes
import org.mongodb.scala.model.{Filters, FindOneAndReplaceOptions, IndexModel, IndexOptions}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.serviceconfigs.model.{DeploymentConfig, Environment, ServiceName}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class DeploymentConfigRepository @Inject()(
  mongoComponent: MongoComponent
)(using
  ec: ExecutionContext
) extends PlayMongoRepository[DeploymentConfig](
  mongoComponent = mongoComponent,
  collectionName = "deploymentConfig",
  domainFormat   = DeploymentConfig.mongoFormat,
  indexes        = Seq(
                     IndexModel(
                       Indexes.ascending("applied", "environment", "name"),
                       IndexOptions().unique(true)
                     ),
                   ),
  replaceIndexes = true,
  extraCodecs    = Codecs.playFormatSumCodecs(Environment.format) :+ Codecs.playFormatCodec(ServiceName.format)
):
  // we replace all the data for each call to replaceEnv
  override lazy val requiresTtlIndex = false

  def replaceEnv(
    environment: Environment,
    configs    : Seq[DeploymentConfig],
    applied    : Boolean
  ): Future[Unit] =
    MongoUtils.replace[DeploymentConfig](
      collection    = collection,
      newVals       = configs,
      oldValsFilter = Filters.and(
                         Filters.equal("environment", environment),
                         Filters.equal("applied"    , applied    )
                       ),
      compareById   = (a, b) =>
                        a.environment == b.environment &&
                        a.serviceName == b.serviceName &&
                        a.applied     == b.applied,
      filterById    = entry =>
                        Filters.and(
                          Filters.equal("environment", environment),
                          Filters.equal("name"       , entry.serviceName),
                          Filters.equal("applied"    , applied)
                        )
    )

  def find(
    applied     : Boolean,
    environments: Seq[Environment] = Seq.empty,
    serviceNames: Seq[ServiceName] = Seq.empty
  ): Future[Seq[DeploymentConfig]] =
    collection
      .find(
        Filters.and(
          Filters.equal("applied", applied),
          Option.when(environments.nonEmpty)(Filters.in("environment", environments                : _*)).getOrElse(Filters.empty()),
          Option.when(serviceNames.nonEmpty)(Filters.in("name"       , serviceNames.map(_.asString): _*)).getOrElse(Filters.empty())
        )
      )
      .toFuture()

  def add(deploymentConfig: DeploymentConfig): Future[Unit] =
    collection
      .findOneAndReplace(
        filter      = Filters.and(
                        Filters.equal("name"       , deploymentConfig.serviceName),
                        Filters.equal("environment", deploymentConfig.environment),
                        Filters.equal("applied"    , deploymentConfig.applied)
                      ),
        replacement = deploymentConfig,
        options     = FindOneAndReplaceOptions().upsert(true)
      )
      .toFutureOption()
      .map(_ => ())

  def delete(deploymentConfig: DeploymentConfig): Future[Unit] =
    collection
      .findOneAndDelete(
        filter = Filters.and(
                   Filters.equal("name"       , deploymentConfig.serviceName),
                   Filters.equal("environment", deploymentConfig.environment),
                   Filters.equal("applied"    , deploymentConfig.applied)
                 )
      )
      .toFutureOption()
      .map(_ => ())
