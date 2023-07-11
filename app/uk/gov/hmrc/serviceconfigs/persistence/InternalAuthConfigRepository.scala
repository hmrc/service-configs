package uk.gov.hmrc.serviceconfigs.persistence

import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.model.Filters.equal
import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.{TransactionConfiguration, Transactions}
import uk.gov.hmrc.serviceconfigs.model.{GrantType, InternalAuthConfig, InternalAuthEnvironment, ServiceName}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class InternalAuthConfigRepository @Inject()(
                                           override val mongoComponent: MongoComponent
                                         )(implicit
                                           ec: ExecutionContext
                                         ) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "internalAuthConfig",
  domainFormat   = InternalAuthConfig.format,
  indexes        = Seq(
    IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx")),
    IndexModel(Indexes.hashed("environment"), IndexOptions().background(true).name("environmentIdx"))
  ),
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format),
                       Codecs.playFormatCodec(InternalAuthEnvironment.format),
                       Codecs.playFormatCodec(GrantType.format))
) with Transactions {

  private implicit val tc: TransactionConfiguration = TransactionConfiguration.strict


  def findByService(serviceName: ServiceName): Future[Seq[InternalAuthConfig]] =
    collection
      .find(equal("service", serviceName))
      .toFuture()

  def putAll(internalAuthConfigs: Seq[InternalAuthConfig]) : Future[Int] =
    withSessionAndTransaction { session =>
      for {
        _ <- collection.deleteMany(session, BsonDocument()).toFuture()
        r <- collection.insertMany(session, internalAuthConfigs).toFuture()
      } yield r.getInsertedIds.size()
    }

}
