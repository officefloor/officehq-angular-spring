-- Contacts are archived rather than deleted: an archived contact drops off the client's contact list
-- but its record is kept.
ALTER TABLE contact ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
