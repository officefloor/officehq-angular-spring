-- A version moved on by each save of a period makes a change to its openings (or other per-person figures) a
-- change of the period itself, so the history (entity_version) keeps it and it reads back as of a date.
ALTER TABLE period ADD COLUMN version BIGINT DEFAULT 0 NOT NULL;
