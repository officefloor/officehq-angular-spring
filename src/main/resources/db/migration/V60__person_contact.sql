-- The person's contact, stored trimmed and lower-cased, unique on its sheet; none when not given.
ALTER TABLE person ADD COLUMN contact VARCHAR(200);
