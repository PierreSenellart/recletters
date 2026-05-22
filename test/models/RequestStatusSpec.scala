package models

import org.scalatestplus.play._

class RequestStatusSpec extends PlaySpec {

  "RequestStatus.parse" should {
    "round-trip all valid values" in {
      RequestStatus.All.foreach { s =>
        RequestStatus.parse(s) mustBe s
      }
    }
    "reject unknown values" in {
      an[IllegalArgumentException] must be thrownBy RequestStatus.parse("approved")
    }
  }

  "RequestStatus constants" should {
    "use the exact strings the CHECK constraint enforces" in {
      RequestStatus.news      mustBe "new"
      RequestStatus.requested mustBe "requested"
      RequestStatus.received  mustBe "received"
      RequestStatus.declined  mustBe "declined"
      RequestStatus.cancelled mustBe "cancelled"
    }
  }
}
