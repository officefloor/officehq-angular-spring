-- A person transferred onto a sheet that has them already by a name or alias is merged into them, and
-- their names join that person's aliases even where one is the same as names are compared: an alias
-- key is no longer unique on a sheet (adding an alias still refuses one in use).
DROP INDEX person_alias_sheet_key;
CREATE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key);
