-- A person merged in on arriving from another sheet brings their name and aliases as aliases, which may equal one the
-- person merged into already has but for case; an alias is no longer kept unique on its sheet by the store, the app
-- refusing one that is already a name or alias there.
DROP INDEX person_alias_sheet_alias;
CREATE INDEX person_alias_sheet_alias ON person_alias (sheet_id, alias_key);
