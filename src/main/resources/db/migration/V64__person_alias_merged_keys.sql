-- A transfer merged into a person who already has one of the arriving names keeps both, so one person
-- may hold aliases sharing a key. Different people still never share a name or alias key on the sheet:
-- that is kept by the app; the index stays for finding people by alias.
DROP INDEX person_alias_sheet_key;
CREATE INDEX person_alias_sheet_key ON person_alias (sheet_id, alias_key);
