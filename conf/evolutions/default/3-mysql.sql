-- Evolution 3, MySQL/MariaDB flavour: per-call email signature (see
-- 3-postgres.sql for rationale).

# --- !Ups

ALTER TABLE call_ ADD COLUMN email_signature_override VARCHAR(255) NULL;

# --- !Downs

ALTER TABLE call_ DROP COLUMN email_signature_override;
