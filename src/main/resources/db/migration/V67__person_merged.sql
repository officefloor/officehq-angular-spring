-- The person a person was merged into; null while they were not. A merged person is kept (their
-- name and history stay) but leaves the sheet: no list, lookup or total shows them again.
ALTER TABLE person ADD COLUMN merged_into BIGINT REFERENCES person (id);
