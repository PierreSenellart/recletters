package models

import java.time.ZonedDateTime

import org.scalatestplus.play._

/** Pure-logic tests for RefereeRequest.actionDate — the date shown in the
  * requests table. Since generateToken stamps status_update on send,
  * status_update is the meaningful timestamp for every status except 'new'.
  */
class RefereeRequestSpec extends PlaySpec {

  private val ts = ZonedDateTime.parse("2026-07-13T10:00:00+02:00")

  private def req(status: RequestStatus.RequestStatus): RefereeRequest =
    RefereeRequest(
      dossier       = Dossier(1L, 1, "Alice", None, None, None),
      email         = "ref@example.org",
      details       = None,
      role          = None,
      name          = None,
      status        = status,
      status_update = ts
    )

  "RefereeRequest.actionDate" should {

    "be empty for a 'new' request that was never sent" in {
      req(RequestStatus.news).actionDate mustBe None
    }

    "be the status_update time once requested" in {
      req(RequestStatus.requested).actionDate mustBe Some(ts)
    }

    "be the status_update time when received" in {
      req(RequestStatus.received).actionDate mustBe Some(ts)
    }

    "be the status_update time when declined" in {
      req(RequestStatus.declined).actionDate mustBe Some(ts)
    }
  }
}
