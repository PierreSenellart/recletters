package models

import org.scalatestplus.play._

class PasswordHasherSpec extends PlaySpec {

  "PasswordHasher.hashPassword + verifyPassword" should {
    "verify a correct password and reject an incorrect one" in {
      val h = PasswordHasher.hashPassword("hunter2-correct-horse")
      PasswordHasher.verifyPassword("hunter2-correct-horse", h) mustBe true
      PasswordHasher.verifyPassword("not-it", h)                mustBe false
    }
    "produce distinct hashes for the same password" in {
      val a = PasswordHasher.hashPassword("same")
      val b = PasswordHasher.hashPassword("same")
      a must not equal b
      PasswordHasher.verifyPassword("same", a) mustBe true
      PasswordHasher.verifyPassword("same", b) mustBe true
    }
  }

  "PasswordHasher.newToken" should {
    "be URL-safe, deterministic length, and unique across calls" in {
      val t = PasswordHasher.newToken()
      t must fullyMatch regex "^[A-Za-z0-9_-]+$"
      t.length must be >= 20
      PasswordHasher.newToken() must not equal t
    }
  }

  "PasswordHasher.sha256 + constantTimeEquals" should {
    "match by hash and use constant-time equality" in {
      val token  = "abcdef"
      val hashA  = PasswordHasher.sha256(token)
      val hashB  = PasswordHasher.sha256(token)
      val hashX  = PasswordHasher.sha256("different")
      PasswordHasher.constantTimeEquals(hashA, hashB) mustBe true
      PasswordHasher.constantTimeEquals(hashA, hashX) mustBe false
    }
  }
}
