-- A sheet's terms: the days after its date a claim is due by; it starts at 14.
ALTER TABLE sheet ADD COLUMN terms_days INT DEFAULT 14 NOT NULL CHECK (terms_days >= 0);
