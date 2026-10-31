-- The sheet's cap: a person's due-total is at most this; null means none.
ALTER TABLE sheet ADD COLUMN cap NUMERIC(14, 2) CHECK (cap >= 0);
