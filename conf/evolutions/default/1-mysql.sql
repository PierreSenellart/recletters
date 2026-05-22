-- Schema for recletters, MySQL/MariaDB flavour.
-- Symlink/copy this file to `1.sql` when running against MySQL or MariaDB.

# --- !Ups

CREATE TABLE call_ (
  id            INTEGER       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  slug          VARCHAR(64)   NOT NULL UNIQUE,
  label         VARCHAR(255)  NOT NULL,
  opens_at      DATETIME      NULL,
  deadline      DATETIME      NOT NULL,
  grace_seconds INTEGER       NOT NULL DEFAULT 0,
  site_name_override  VARCHAR(255) NULL,
  email_from_override VARCHAR(255) NULL,
  is_archived   BOOLEAN       NOT NULL DEFAULT FALSE
);

CREATE TABLE dossier (
  id           INTEGER       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  call_id      INTEGER       NOT NULL,
  name         TEXT          NOT NULL,
  external_ref VARCHAR(255)  NULL,
  details      TEXT          NULL,
  url          TEXT          NULL,
  FOREIGN KEY (call_id) REFERENCES call_ (id),
  UNIQUE (call_id, external_ref)
);

CREATE INDEX dossier_call_id_idx ON dossier (call_id);
CREATE INDEX dossier_external_ref_idx ON dossier (external_ref);

CREATE TABLE referee_request (
  dossier        INTEGER     NOT NULL,
  email          VARCHAR(255) NOT NULL,
  details        TEXT        NULL,
  role           VARCHAR(64) NULL,
  status         VARCHAR(16) NOT NULL DEFAULT 'new',
  status_update  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (dossier, email),
  FOREIGN KEY (dossier) REFERENCES dossier (id),
  CONSTRAINT referee_request_status_chk
    CHECK (status IN ('new','requested','received','declined','cancelled'))
);

CREATE TABLE referee_letter (
  dossier   INTEGER      NOT NULL,
  email     VARCHAR(255) NOT NULL,
  name      TEXT         NOT NULL,
  letter    LONGBLOB,
  time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (dossier, email),
  FOREIGN KEY (dossier, email) REFERENCES referee_request (dossier, email)
);

CREATE TABLE referee_token (
  dossier       INTEGER      NOT NULL,
  email         VARCHAR(255) NOT NULL,
  token_hash    VARBINARY(64) NOT NULL,
  token_issued  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  expires_at    DATETIME     NOT NULL,
  PRIMARY KEY (dossier, email),
  FOREIGN KEY (dossier, email) REFERENCES referee_request (dossier, email),
  INDEX referee_token_hash_idx (token_hash)
);

CREATE TABLE users (
  id           INTEGER      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  email        VARCHAR(255) NOT NULL UNIQUE,
  last_name    TEXT,
  first_name   TEXT,
  password     TEXT,
  token_hash   VARBINARY(64),
  token_issued DATETIME,
  CONSTRAINT users_name_chk
    CHECK (first_name IS NOT NULL OR last_name IS NOT NULL)
);

# --- !Downs

DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS referee_token;
DROP TABLE IF EXISTS referee_letter;
DROP TABLE IF EXISTS referee_request;
DROP TABLE IF EXISTS dossier;
DROP TABLE IF EXISTS call_;
