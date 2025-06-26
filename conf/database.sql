-- For password and token management
CREATE EXTENSION pgcrypto;

CREATE TABLE dossier (
  id SERIAL PRIMARY KEY NOT NULL,
  year SMALLINT NOT NULL,
  name TEXT NOT NULL,
  details TEXT,
  url TEXT
);

CREATE TYPE request_status AS ENUM (
  'new',
  'requested',
  'received',
  'declined',
  'cancelled'
);

CREATE TABLE referee_request (
  dossier INT REFERENCES dossier (id) NOT NULL,
  email TEXT NOT NULL,
  details TEXT,
  status request_status NOT NULL DEFAULT 'new',
  status_update TIMESTAMP NOT NULL DEFAULT NOW (),
  PRIMARY KEY (dossier, email)
);

CREATE TABLE referee_letter (
  dossier INT NOT NULL,
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  letter bytea,
  time TIMESTAMP NOT NULL DEFAULT NOW (),
  PRIMARY KEY (dossier, email),
  FOREIGN KEY (dossier, email) REFERENCES referee_request (dossier, email)
);

CREATE TABLE referee_token (
  dossier INT REFERENCES dossier (id) NOT NULL,
  email TEXT NOT NULL,
  token TEXT NOT NULL,
  token_issued TIMESTAMP NOT NULL DEFAULT NOW (),
  PRIMARY KEY (dossier, email),
  FOREIGN KEY (dossier, email) REFERENCES referee_request (dossier, email)
);

CREATE TABLE users (
  id SERIAL PRIMARY KEY,
  email TEXT NOT NULL UNIQUE,
  last_name TEXT,
  first_name TEXT,
  password TEXT,
  token TEXT,
  token_issued TIMESTAMP,
  CHECK (
    first_name IS NOT NULL
    OR last_name IS NOT NULL
  )
);
