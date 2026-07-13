-- Evolution 2, MySQL/MariaDB flavour: allow several concurrent tokens per
-- referee (see 2-postgres.sql for rationale). Widen the primary key to include
-- the token hash. The FK (dossier, email) -> referee_request needs a covering
-- index; add an explicit one before dropping the old (dossier, email) primary
-- key so the constraint is never left uncovered mid-migration.

# --- !Ups

ALTER TABLE referee_token ADD INDEX referee_token_req_idx (dossier, email);
ALTER TABLE referee_token DROP PRIMARY KEY, ADD PRIMARY KEY (dossier, email, token_hash);

# --- !Downs

ALTER TABLE referee_token DROP PRIMARY KEY, ADD PRIMARY KEY (dossier, email);
ALTER TABLE referee_token DROP INDEX referee_token_req_idx;
