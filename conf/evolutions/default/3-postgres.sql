-- Evolution 3, PostgreSQL flavour: per-call email signature. When set, it
-- replaces the global `email_signature` setting (and the default signature
-- derived from the site name) at the bottom of referee-facing emails.

# --- !Ups

ALTER TABLE call_ ADD COLUMN email_signature_override VARCHAR(255);

# --- !Downs

ALTER TABLE call_ DROP COLUMN email_signature_override;
