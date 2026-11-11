-- A transfer that merges a person into one of the same name joins their names to that person's aliases, which may
-- then hold the same key more than once. Each alias takes a slot: 0 for one added, the next free slot for one joined,
-- so an alias added is still unique among the aliases on its sheet.
ALTER TABLE person_alias ADD COLUMN alias_slot INT NOT NULL DEFAULT 0;
DROP INDEX person_alias_sheet_key;
CREATE UNIQUE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key, alias_slot);
