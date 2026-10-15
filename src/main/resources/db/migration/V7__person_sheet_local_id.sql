-- People are numbered within their own sheet, so each sheet has its own person 1, 2, ...; the
-- surrogate id stays the internal key (and the key patrons link by).
ALTER TABLE person ADD COLUMN local_id BIGINT;
UPDATE person SET local_id = id;
ALTER TABLE person ALTER COLUMN local_id SET NOT NULL;
ALTER TABLE person ADD COLUMN sheet_key VARCHAR(255) GENERATED ALWAYS AS (COALESCE(sheet_id, ''));
CREATE UNIQUE INDEX person_sheet_local_id ON person (sheet_key, local_id);
