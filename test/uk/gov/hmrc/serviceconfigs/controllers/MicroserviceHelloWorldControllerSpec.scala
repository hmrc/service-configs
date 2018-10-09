package uk.gov.hmrc.serviceconfigs.controllers

import org.scalatest.{Matchers, WordSpec}
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.http.Status
import play.api.test.FakeRequest
import play.api.test.Helpers._

class MicroserviceHelloWorldControllerSpec extends WordSpec with Matchers with GuiceOneAppPerSuite {

  val fakeRequest = FakeRequest("GET", "/")

  "GET /" should {
    "return 200" in {
      val controller = new MicroserviceHelloWorld()
      val result = controller.hello()(fakeRequest)
      status(result) shouldBe Status.OK
    }
  }

}
