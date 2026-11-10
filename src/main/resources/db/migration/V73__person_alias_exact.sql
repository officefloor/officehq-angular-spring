-- A person's aliases are kept as written: a person merged in from another sheet may bring an alias
-- that differs from one they already have only in case, and both are kept.
ALTER TABLE person_alias DROP CONSTRAINT person_alias_once;
ALTER TABLE person_alias ADD CONSTRAINT person_alias_once UNIQUE (person_id, alias);
