-- Whether an open or pending claim is held: while it is, and unbound by a statement, it counts in
-- no live total, as a held person's claims do. The sheet's history keeps it too.
ALTER TABLE claim ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim_revision ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
