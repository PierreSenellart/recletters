package models

import java.security.{MessageDigest, SecureRandom}
import at.favre.lib.crypto.bcrypt.BCrypt

/** All hashing and random-bytes generation happens in Scala so the SQL stays
  * portable across PostgreSQL and MySQL/MariaDB (no pgcrypto, no MD5 builtins).
  */
object PasswordHasher {
  private val rng    = new SecureRandom()
  private val Cost   = 12

  def hashPassword(plain: String): String =
    BCrypt.withDefaults().hashToString(Cost, plain.toCharArray)

  def verifyPassword(plain: String, hashed: String): Boolean = {
    if (hashed == null || hashed.isEmpty) return false
    BCrypt.verifyer().verify(plain.toCharArray, hashed.toCharArray).verified
  }

  /** URL-safe random token: 20 bytes → 27-char base64url string. */
  def newToken(): String = {
    val bytes = new Array[Byte](20)
    rng.nextBytes(bytes)
    java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  /** SHA-256 hash of a token, returned as raw bytes for BYTEA / VARBINARY storage. */
  def sha256(token: String): Array[Byte] = {
    val md = MessageDigest.getInstance("SHA-256")
    md.digest(token.getBytes("UTF-8"))
  }

  /** Constant-time equality. Never use Array.equals on hashes. */
  def constantTimeEquals(a: Array[Byte], b: Array[Byte]): Boolean =
    MessageDigest.isEqual(a, b)
}
