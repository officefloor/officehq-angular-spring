-- The days after its date a claim is due by; a sheet starts at 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL;
