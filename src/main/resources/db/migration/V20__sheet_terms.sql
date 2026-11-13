-- A sheet's terms (whole days): a claim is due by its date plus the terms. A sheet starts at 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL;
