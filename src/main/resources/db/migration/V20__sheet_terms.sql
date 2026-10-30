-- The days a claim has to be paid by, from its date; it starts at 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL;
