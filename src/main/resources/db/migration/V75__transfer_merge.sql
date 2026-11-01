-- A person transferred to a sheet that already has a person of their name or alias is merged into
-- them there, so the sheet may keep, merged away, a person whose name is one a person on it has, and a
-- person may hold, as aliases, names that are the same as names are compared. A name's uniqueness on
-- the sheet is kept among the people not merged away (live_name_key is null for those merged away);
-- an alias's, as names are compared, is kept by the service, the sheet locked.
DROP INDEX person_sheet_name;
ALTER TABLE person ADD COLUMN live_name_key VARCHAR(200);
UPDATE person SET live_name_key = name_key WHERE merged_into IS NULL;
CREATE UNIQUE INDEX person_sheet_name ON person (sheet_id, live_name_key);
DROP INDEX person_alias_sheet_key;
CREATE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key);
CREATE UNIQUE INDEX person_alias_sheet_alias ON person_alias (sheet_id, alias);
