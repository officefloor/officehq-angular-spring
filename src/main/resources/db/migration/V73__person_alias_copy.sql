-- A person merged with another may hold two aliases that compare equal (Addie and addie): alias_copy
-- tells them apart on the one person (0 for the first, 1 for the next, ...), so the index still refuses
-- an alias, as compared, held by two people at once.
ALTER TABLE person_alias ADD COLUMN alias_copy INT DEFAULT 0 NOT NULL;
DROP INDEX person_alias_sheet_key;
CREATE UNIQUE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key, alias_copy);
