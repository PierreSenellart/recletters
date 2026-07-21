-- Evolution 2, PostgreSQL flavour: allow several concurrent tokens per referee.
-- A reminder issues a fresh token but no longer invalidates the earlier one, so
-- the link in any request email the referee received stays usable until it
-- expires on its own. Widen the primary key to include the token hash.

# --- !Ups

ALTER TABLE referee_token DROP CONSTRAINT referee_token_pkey;
ALTER TABLE referee_token ADD PRIMARY KEY (dossier, email, token_hash);

# --- !Downs

ALTER TABLE referee_token DROP CONSTRAINT referee_token_pkey;
ALTER TABLE referee_token ADD PRIMARY KEY (dossier, email);
