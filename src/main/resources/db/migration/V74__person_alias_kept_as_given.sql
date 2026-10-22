-- A person who arrives on a sheet by transfer and is merged into someone there joins their aliases to
-- that person's, so one person may hold aliases that differ only in case: an alias is now kept once
-- per person as it is written, rather than once per key.
ALTER TABLE person_alias DROP PRIMARY KEY;
ALTER TABLE person_alias ADD PRIMARY KEY (person_id, alias);
