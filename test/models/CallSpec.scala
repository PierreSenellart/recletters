package models

import org.scalatestplus.play._
import java.time.ZonedDateTime

class CallSpec extends PlaySpec {
  private def baseCall(
      deadlineOffsetDays: Long,
      opensOffsetDays: Option[Long] = None,
      graceSeconds: Int = 0
  ): Call = {
    val now = ZonedDateTime.now()
    Call(
      id            = 1,
      slug          = "test",
      label         = "Test call",
      opens_at      = opensOffsetDays.map(off => now.plusDays(off)),
      deadline      = now.plusDays(deadlineOffsetDays),
      grace_seconds = graceSeconds,
      site_name_override  = None,
      email_from_override = None,
      is_archived   = false
    )
  }

  "Call.isOpenAt" should {
    "consider a call open between opens_at and deadline" in {
      val c   = baseCall(deadlineOffsetDays = 7, opensOffsetDays = Some(-1))
      c.isOpenAt(ZonedDateTime.now()) mustBe true
    }
    "consider a call closed after the deadline + grace" in {
      val c   = baseCall(deadlineOffsetDays = -1, graceSeconds = 0)
      c.isOpenAt(ZonedDateTime.now()) mustBe false
    }
    "extend the deadline by the grace period" in {
      val c = baseCall(deadlineOffsetDays = -1, graceSeconds = 3 * 24 * 3600)
      c.isOpenAt(ZonedDateTime.now()) mustBe true
    }
    "consider a future-opens_at call closed until that date" in {
      val c = baseCall(deadlineOffsetDays = 30, opensOffsetDays = Some(1))
      c.isOpenAt(ZonedDateTime.now()) mustBe false
    }
  }
}
