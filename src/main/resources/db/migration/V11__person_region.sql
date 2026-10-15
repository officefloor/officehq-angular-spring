-- A person's region: a short code typed in (like N1 or S2); absent until given.
ALTER TABLE person ADD COLUMN region VARCHAR(16);
