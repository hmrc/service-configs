package uk.gov.hmrc.serviceconfigs.persistence

import org.mongodb.scala.model.{IndexModel, IndexOptions, Indexes}
import uk.gov.hmrc.mongo.MongoComponent
import uk.gov.hmrc.mongo.play.json.{Codecs, PlayMongoRepository}
import uk.gov.hmrc.mongo.transaction.Transactions
import uk.gov.hmrc.serviceconfigs.model.{Dashboard, ServiceName}

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class InternalAuthConfigRepository @Inject()(
                                           override val mongoComponent: MongoComponent
                                         )(implicit
                                           ec: ExecutionContext
                                         ) extends PlayMongoRepository(
  mongoComponent = mongoComponent,
  collectionName = "internalAuthConfig",
  domainFormat   = Dashboard.format,
  indexes        = Seq(
    IndexModel(Indexes.hashed("service"), IndexOptions().background(true).name("serviceIdx"))
  ),
  extraCodecs    = Seq(Codecs.playFormatCodec(ServiceName.format))
) with Transactions {

}
