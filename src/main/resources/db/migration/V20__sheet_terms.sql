-- The days a claim has to be settled in: its due-by is its date plus these; it starts at 14.
ALTER TABLE sheet ADD COLUMN terms INT DEFAULT 14 NOT NULL CHECK (terms >= 0);
