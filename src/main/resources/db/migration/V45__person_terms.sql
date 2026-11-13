-- A person's own terms (whole days); null for none, when their claims take their grade's or the sheet's.
ALTER TABLE person ADD COLUMN terms INT;
ALTER TABLE person ADD CONSTRAINT person_terms_not_negative CHECK (terms IS NULL OR terms >= 0);
