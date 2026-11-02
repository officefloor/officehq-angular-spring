-- An OPEN or PENDING claim may be held: while held and not bound by a statement it is kept out of the live totals.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
