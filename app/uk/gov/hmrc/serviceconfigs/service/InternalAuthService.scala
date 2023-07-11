package uk.gov.hmrc.serviceconfigs.service

import play.api.Logging
import uk.gov.hmrc.serviceconfigs.connector.ConfigAsCodeConnector

import java.util.zip.ZipInputStream
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class InternalAuthService @Inject()(
configAsCodeConnector: ConfigAsCodeConnector
)(implicit ec: ExecutionContext
) extends Logging {

  def updateInternalAuth(): Future[Unit] = {
    for {
      zip <- configAsCodeConnector.streamInternalAuth()
    }
  }

  def foo(z: ZipInputStream) = {

  }

}
