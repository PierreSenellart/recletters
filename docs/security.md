# Security

## Threat model

`recletters` holds:

- recommendation letters (confidential by convention; their leak would damage
  applicants' and referees' relationships and reputations);
- committee credentials (bcrypt-hashed passwords + SHA-256-hashed
  password-reset tokens);
- referee submission tokens (SHA-256-hashed; the bearer is the legitimate
  uploader by construction).

Defended against (out of the box):

- Session-cookie theft: `Secure`, `HttpOnly`, `SameSite=Lax` cookies.
- CSRF on state-changing committee endpoints: Play's `CSRFFilter` is enabled.
- Open-redirect on login: `?path=...` accepts only same-origin paths.
- Email enumeration via the password-reset form: the response is constant
  regardless of whether the address exists.
- Plaintext token reuse on the server: only SHA-256 of referee tokens lives
  in the database, just as for password-reset tokens.
- Tampered uploads: server-side PDF magic-byte check + 10 MiB size cap.
- Clickjacking / framing: `X-Frame-Options: DENY` + CSP `frame-ancestors`.
- MIME sniffing: `X-Content-Type-Options: nosniff` on letter downloads.
- Timing attacks on the API bearer token and on reset-token lookup:
  constant-time comparison via `MessageDigest.isEqual`.

Out of scope (deferred):

- Brute-force resistance on `/authenticate` and `/init_password`: no built-in
  rate limiter. Front with fail2ban or a WAF if exposed to the open Internet.
- DoS on the upload endpoint beyond Play's request-body size limit.
- Auditing / immutable log of who downloaded which letter.
- Encryption at rest in the database: handled by the DBMS or filesystem.

## Operational checklist

- HTTPS only. HSTS is on; serving over HTTP will break.
- Rotate `api_token` (in `secrets.conf`) whenever a committee member or
  importer leaves.
- Back up the database before each Evolutions upgrade.
- Keep `secrets.conf` out of git (it is in `.gitignore`).
- Restrict `/api/*` and `/letter` to a private network if possible: the
  bearer token is the only auth in front of the bulk-ingest endpoint, and the
  letter download endpoint relies on session auth only.
